package com.stove.order;

import static com.stove.order.OrderFixtures.completed;
import static com.stove.order.OrderFixtures.id;
import static com.stove.order.OrderFixtures.order;
import static com.stove.order.OrderFixtures.result;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.kafka.EventHeaders;
import com.stove.common.kafka.contract.EventType;
import com.stove.common.kafka.contract.Topics;
import com.stove.common.kafka.contract.payload.LicenseGrantedEvent;
import com.stove.common.messaging.outbox.OutboxEventRepository;
import com.stove.common.messaging.outbox.OutboxMetrics;
import com.stove.common.messaging.outbox.OutboxRelay;
import com.stove.order.api.listener.LicenseEventListener;
import com.stove.order.core.domain.Order;
import com.stove.order.core.domain.OrderRepository;
import com.stove.order.core.domain.RefundRepository;
import com.stove.order.core.domain.RefundStatus;
import com.stove.order.core.service.ApplyPaymentResult;
import com.stove.order.core.service.RequestRefund;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

/**
 * 결정 129: Kafka 에서 원본이 사라진 OrderPaid 를 Order 의 원래 Outbox 행으로 같은 ID 재발행한다.
 * 받는 쪽으로서는 License 가 재발행한 LicenseGranted 가 미처리면 PENDING_LICENSE_GRANT 를 정지 요청으로 잇고,
 * 이미 처리됐으면 효과가 없음을 본다. 구성은 OrderRecoveryOpsIntegrationTest 와 같게 두어 컨텍스트를 함께 쓴다.
 */
@TestPropertySource(properties = "stove.messaging.ops.enabled=true")
class OrderOutboxRepublishIntegrationTest extends OrderIntegrationTestBase {
    private static final String COLUMNS = "event_id,event_type,aggregate_id,payload,topic,partition_key,trace_parent,created_at";
    @Autowired OrderRepository orders;
    @Autowired RefundRepository refunds;
    @Autowired ApplyPaymentResult apply;
    @Autowired RequestRefund request;
    @Autowired LicenseEventListener licenseEvents;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired ConsumerFactory<String, String> consumers;
    @Autowired OutboxEventRepository rows;
    @Autowired PlatformTransactionManager transactions;
    @Autowired KafkaListenerEndpointRegistry listeners;
    @LocalServerPort int port;

    @BeforeEach
    void clean() {
        listeners.stop();
        jdbc.update("DELETE FROM order_refund_attempt ORDER BY id DESC");
        jdbc.update("DELETE FROM order_license_grant");
        jdbc.update("DELETE FROM order_refund");
        jdbc.update("DELETE FROM purchase_order");
        jdbc.update("DELETE FROM processed_event");
        jdbc.update("DELETE FROM outbox_event");
        jdbc.update("DELETE FROM outbox_republish");
    }

    @Test
    void adminRepublishesTheSentOrderPaidWithTheSameIdBodyAndKeyAndRecordsWhoWhenWhy() {
        Order paid = paid();
        relay().relay();
        Map<String, Object> original = jdbc.queryForMap("SELECT " + COLUMNS + " FROM outbox_event");
        String eventId = original.get("event_id").toString();
        assertThat(original.get("event_type")).isEqualTo(EventType.ORDER_PAID);
        assertThat(original.get("partition_key")).isEqualTo(paid.getOrderNo());
        String path = "/api/v1/ops/outbox/" + eventId + "/republish";

        assertThat(post(path, "MEMBER", reason()).status()).isEqualTo(403);
        assertThat(post(path, "ADMIN", Map.of()).status()).isEqualTo(400);
        assertThat(status(eventId)).isEqualTo("SENT");

        Response accepted = post(path, "ADMIN", reason());
        assertThat(accepted.status()).isEqualTo(200);
        assertThat(accepted.data().path("eventType").asText()).isEqualTo(EventType.ORDER_PAID);
        assertThat(accepted.data().path("aggregateId").asText()).isEqualTo(paid.getOrderNo());
        assertThat(status(eventId)).isEqualTo("PENDING");
        assertThat(jdbc.queryForMap("SELECT event_id,republished_by,reason FROM outbox_republish"))
                .isEqualTo(Map.of("event_id", eventId, "republished_by", "operator",
                        "reason", "License DLT 보존 기간 경과, License Inbox 없음 확인"));

        relay().relay();
        assertThat(status(eventId)).isEqualTo("SENT");
        assertThat(jdbc.queryForMap("SELECT " + COLUMNS + " FROM outbox_event")).isEqualTo(original);
        List<ConsumerRecord<String, String>> published = published(Topics.ORDER, eventId, 2);
        assertThat(published).hasSize(2).allSatisfy(found -> {
            assertThat(found.key()).isEqualTo(paid.getOrderNo());
            assertThat(found.value()).isEqualTo(original.get("payload"));
        });
    }

    @Test
    void deadRowsMissingRowsAndTypesOutsideTheListAreRejected() {
        String dead = insert(EventType.ORDER_PAID, "DEAD");
        String other = insert(EventType.LICENSE_SUSPENSION_REQUESTED, "SENT");

        assertThat(post("/api/v1/ops/outbox/" + dead + "/republish", "ADMIN", reason()).status())
                .as("DEAD 는 requeue 로 되살린다").isEqualTo(404);
        assertThat(post("/api/v1/ops/outbox/" + id() + "/republish", "ADMIN", reason()).status()).isEqualTo(404);
        assertThat(post("/api/v1/ops/outbox/" + other + "/republish", "ADMIN", reason()).status()).isEqualTo(400);
        assertThat(status(dead)).isEqualTo("DEAD");
        assertThat(status(other)).isEqualTo("SENT");
        assertThat(count("outbox_republish")).isZero();
    }

    @Test
    void aRepublishedGrantContinuesTheWaitingRefundToSuspensionAndARepeatHasNoEffect() throws Exception {
        Order paid = paid();
        request.execute("usr_buyer", paid.getOrderNo());
        assertThat(refundStatus(paid)).isEqualTo(RefundStatus.PENDING_LICENSE_GRANT);
        long before = count("outbox_event");
        LicenseGrantedEvent granted = new LicenseGrantedEvent(id(), Instant.now().truncatedTo(ChronoUnit.MICROS), id(),
                paid.getOrderNo(), paid.getBuyerSubject(), paid.getProductId(), paid.getProductCode(), paid.getPaymentId());

        licenseEvents.receive(licenseGranted(granted));

        assertThat(refundStatus(paid)).isEqualTo(RefundStatus.PENDING_LICENSE_SUSPENSION);
        assertThat(count("order_license_grant")).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT event_type FROM outbox_event ORDER BY id", String.class))
                .as("지급 확인이 기존 정지 요청으로 이어진다").hasSize((int) before + 1)
                .last().isEqualTo(EventType.LICENSE_SUSPENSION_REQUESTED);
        Map<String, List<Map<String, Object>>> afterFirst = snapshot();

        licenseEvents.receive(licenseGranted(granted));

        assertThat(snapshot()).as("이미 처리된 재발행은 받는 쪽에서 효과가 없다").isEqualTo(afterFirst);
    }

    private Order paid() {
        Order stored = orders.saveAndFlush(order());
        apply.execute(EventType.ORDER_PAYMENT_COMPLETED, result(completed(stored)));
        return orders.findByOrderNo(stored.getOrderNo()).orElseThrow();
    }

    private RefundStatus refundStatus(Order paid) {
        return refunds.findByOrderNo(paid.getOrderNo()).orElseThrow().getStatus();
    }

    private ConsumerRecord<String, String> licenseGranted(LicenseGrantedEvent granted) throws Exception {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(Topics.LICENSE, 0, 0L, granted.orderNo(),
                mapper.writeValueAsString(granted));
        record.headers().add(EventHeaders.EVENT_ID, granted.eventId().getBytes(StandardCharsets.UTF_8));
        record.headers().add(EventHeaders.EVENT_TYPE, granted.eventType().getBytes(StandardCharsets.UTF_8));
        return record;
    }

    private String insert(String type, String status) {
        String eventId = id();
        jdbc.update("""
                INSERT INTO outbox_event (event_id,aggregate_type,aggregate_id,event_type,topic,partition_key,payload,
                                          status,retry_count,created_at,sent_at)
                VALUES (?, 'Order', 'order-1', ?, ?, 'order-1', '{}', ?, 0, NOW(6), NOW(6))
                """, eventId, type, Topics.ORDER, status);
        return eventId;
    }

    private Map<String, List<Map<String, Object>>> snapshot() {
        Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
        for (String table : List.of("purchase_order", "order_refund", "order_license_grant", "order_refund_attempt",
                "outbox_event", "processed_event")) {
            result.put(table, jdbc.queryForList("SELECT * FROM " + table + " ORDER BY id"));
        }
        return result;
    }

    private List<ConsumerRecord<String, String>> published(String topic, String eventId, int expected) {
        Map<String, Object> config = new HashMap<>(consumers.getConfigurationProperties());
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        try (Consumer<String, String> observer = new DefaultKafkaConsumerFactory<String, String>(config)
                .createConsumer("republish-observer-" + UUID.randomUUID())) {
            observer.assign(observer.partitionsFor(topic).stream()
                    .map(partition -> new TopicPartition(topic, partition.partition())).toList());
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (found.size() < expected && System.nanoTime() < deadline) {
                for (ConsumerRecord<String, String> record : observer.poll(Duration.ofMillis(500))) {
                    if (eventId.equals(new String(record.headers().lastHeader(EventHeaders.EVENT_ID).value(),
                            StandardCharsets.UTF_8))) {
                        found.add(record);
                    }
                }
            }
        }
        return found;
    }

    private OutboxRelay relay() {
        return new OutboxRelay(rows, kafka, 200, 1, 1,
                new OutboxMetrics(new SimpleMeterRegistry(), rows), new TransactionTemplate(transactions));
    }

    private static Map<String, String> reason() {
        return Map.of("reason", "License DLT 보존 기간 경과, License Inbox 없음 확인");
    }

    private String status(String eventId) {
        return jdbc.queryForObject("SELECT status FROM outbox_event WHERE event_id=?", String.class, eventId);
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private Response post(String path, String role, Object body) {
        return RestClient.create("http://127.0.0.1:" + port).method(HttpMethod.POST).uri(path)
                .header("Authorization", "Bearer " + token("operator", role))
                .contentType(MediaType.APPLICATION_JSON).body(body)
                .exchange((sent, response) -> {
                    byte[] bytes = response.getBody().readAllBytes();
                    JsonNode json = bytes.length == 0 ? mapper.createObjectNode() : mapper.readTree(bytes);
                    return new Response(response.getStatusCode().value(), json);
                });
    }

    private record Response(int status, JsonNode body) {
        JsonNode data() {
            return body.path("data");
        }
    }
}
