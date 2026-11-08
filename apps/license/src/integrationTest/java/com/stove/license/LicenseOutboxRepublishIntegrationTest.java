package com.stove.license;

import static com.stove.license.LicenseFixtures.event;
import static com.stove.license.LicenseFixtures.paid;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.kafka.EventHeaders;
import com.stove.common.kafka.contract.EventType;
import com.stove.common.kafka.contract.Topics;
import com.stove.common.kafka.contract.payload.OrderPaidEvent;
import com.stove.common.messaging.outbox.OutboxEventRepository;
import com.stove.common.messaging.outbox.OutboxMetrics;
import com.stove.common.messaging.outbox.OutboxRelay;
import com.stove.license.api.listener.OrderEventListener;
import com.stove.license.core.domain.PaidOrder;
import com.stove.license.core.service.GrantLicense;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
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
 * 결정 129: Kafka 에서 원본이 사라진 LicenseGranted 를 License 의 원래 Outbox 행으로 같은 ID 재발행한다.
 * 받는 쪽으로서는 Order 가 재발행한 OrderPaid 가 이미 처리된 경우 Inbox 에서 효과가 없음을 본다.
 */
@TestPropertySource(properties = "stove.messaging.ops.enabled=true")
class LicenseOutboxRepublishIntegrationTest extends LicenseIntegrationTestBase {
    private static final String COLUMNS = "event_id,event_type,aggregate_id,payload,topic,partition_key,trace_parent,created_at";
    @Autowired GrantLicense grant;
    @Autowired OrderEventListener orderEvents;
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
        jdbc.update("DELETE FROM license_suspension");
        jdbc.update("DELETE FROM license_registration"); jdbc.update("DELETE FROM product_license");
        jdbc.update("DELETE FROM processed_event");
        jdbc.update("DELETE FROM outbox_event");
        jdbc.update("DELETE FROM outbox_republish");
    }

    @Test
    void adminRepublishesTheSentGrantWithTheSameIdBodyAndKeyAndRecordsWhoWhenWhy() throws Exception {
        grant.execute(paid());
        relay().relay();
        Map<String, Object> original = jdbc.queryForMap("SELECT " + COLUMNS + " FROM outbox_event");
        String eventId = original.get("event_id").toString();
        assertThat(original.get("event_type")).isEqualTo(EventType.LICENSE_GRANTED);
        Timestamp firstSentAt = jdbc.queryForObject("SELECT sent_at FROM outbox_event", Timestamp.class);
        String path = "/api/v1/ops/outbox/" + eventId + "/republish";

        assertThat(post(path, "MEMBER", reason()).status()).isEqualTo(403);
        assertThat(post(path, "ADMIN", Map.of("reason", " ")).status()).isEqualTo(400);
        assertThat(status(eventId)).isEqualTo("SENT");
        assertThat(count("outbox_republish")).isZero();

        Response accepted = post(path, "ADMIN", reason());
        assertThat(accepted.status()).isEqualTo(200);
        assertThat(accepted.data().path("eventId").asText()).isEqualTo(eventId);
        assertThat(accepted.data().path("status").asText()).isEqualTo("PENDING");
        assertThat(accepted.data().path("republishedBy").asText()).isEqualTo("operator");
        assertThat(accepted.data().has("payload")).isFalse();
        assertThat(status(eventId)).isEqualTo("PENDING");
        Map<String, Object> record = jdbc.queryForMap(
                "SELECT event_id,event_type,republished_by,reason,republished_at FROM outbox_republish");
        assertThat(record.get("event_id")).isEqualTo(eventId);
        assertThat(record.get("event_type")).isEqualTo(EventType.LICENSE_GRANTED);
        assertThat(jdbc.queryForObject("SELECT previous_sent_at FROM outbox_republish", Timestamp.class))
                .as("이전 발행 시각은 재발행 기록이 보존한다").isEqualTo(firstSentAt);
        assertThat(record.get("republished_by")).isEqualTo("operator");
        assertThat(record.get("reason")).isEqualTo("Order DLT 보존 기간 경과, Order Inbox 없음 확인");
        assertThat(record.get("republished_at")).isNotNull();
        assertThat(post(path, "ADMIN", reason()).status()).as("발행 대기 중인 행은 다시 재발행하지 않는다").isEqualTo(404);
        assertThat(count("outbox_republish")).isEqualTo(1);

        relay().relay();
        assertThat(status(eventId)).isEqualTo("SENT");
        assertThat(jdbc.queryForMap("SELECT " + COLUMNS + " FROM outbox_event")).isEqualTo(original);
        assertThat(count("outbox_event")).as("새 이벤트 ID 를 만들지 않는다").isEqualTo(1);
        List<ConsumerRecord<String, String>> published = published(original.get("topic").toString(), eventId, 2);
        assertThat(published).hasSize(2).allSatisfy(found -> {
            assertThat(found.key()).isEqualTo(original.get("partition_key"));
            assertThat(found.value()).isEqualTo(original.get("payload"));
            assertThat(header(found, EventHeaders.EVENT_TYPE)).isEqualTo(EventType.LICENSE_GRANTED);
        });
    }

    @Test
    void missingRowsAndTypesOutsideTheListAreRejectedWithoutChangingTheRow() {
        assertThat(post("/api/v1/ops/outbox/" + UUID.randomUUID() + "/republish", "ADMIN", reason()).status())
                .isEqualTo(404);
        String other = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO outbox_event (event_id,aggregate_type,aggregate_id,event_type,topic,partition_key,payload,
                                          status,retry_count,created_at,sent_at)
                VALUES (?, 'License', 'order-1', ?, ?, 'order-1', '{}', 'SENT', 0, NOW(6), NOW(6))
                """, other, EventType.LICENSE_SUSPENDED, Topics.LICENSE);
        Response rejected = post("/api/v1/ops/outbox/" + other + "/republish", "ADMIN", reason());
        assertThat(rejected.status()).isEqualTo(400);
        assertThat(rejected.body().path("error").path("code").asText()).isEqualTo("INVALID_REQUEST");
        assertThat(status(other)).isEqualTo("SENT");
        assertThat(count("outbox_republish")).isZero();
    }

    @Test
    void aRepublishedOrderPaidThatLicenseAlreadyProcessedHasNoEffect() throws Exception {
        PaidOrder order = paid();
        OrderPaidEvent paid = event(order);
        orderEvents.receive(orderPaid(paid));
        Map<String, List<Map<String, Object>>> afterFirst = snapshot();
        assertThat(count("product_license")).isEqualTo(1);
        assertThat(count("processed_event")).isEqualTo(1);
        assertThat(count("outbox_event")).isEqualTo(1);

        orderEvents.receive(orderPaid(paid));

        assertThat(snapshot()).isEqualTo(afterFirst);
    }

    private ConsumerRecord<String, String> orderPaid(OrderPaidEvent paid) throws Exception {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(Topics.ORDER, 0, 0L, paid.orderNo(),
                mapper.writeValueAsString(paid));
        record.headers().add(EventHeaders.EVENT_ID, paid.eventId().getBytes(StandardCharsets.UTF_8));
        record.headers().add(EventHeaders.EVENT_TYPE, paid.eventType().getBytes(StandardCharsets.UTF_8));
        return record;
    }

    private Map<String, List<Map<String, Object>>> snapshot() {
        Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
        for (String table : List.of("product_license", "license_registration", "processed_event", "outbox_event")) {
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
                    if (eventId.equals(header(record, EventHeaders.EVENT_ID))) {
                        found.add(record);
                    }
                }
            }
        }
        return found;
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        return new String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
    }

    private OutboxRelay relay() {
        return new OutboxRelay(rows, kafka, 200, 1, 1,
                new OutboxMetrics(new SimpleMeterRegistry(), rows), new TransactionTemplate(transactions));
    }

    private static Map<String, String> reason() {
        return Map.of("reason", "Order DLT 보존 기간 경과, Order Inbox 없음 확인");
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
                .exchange((request, response) -> {
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
