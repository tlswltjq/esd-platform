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
import com.stove.common.kafka.contract.DomainEvent;
import com.stove.common.kafka.contract.payload.LicenseApprovalCorrectedEvent;
import com.stove.common.kafka.contract.payload.LicenseApprovalCorrectionRequestedEvent;
import com.stove.common.kafka.contract.payload.LicenseGrantedEvent;
import com.stove.common.kafka.contract.payload.LicenseRestorationRequestedEvent;
import com.stove.common.kafka.contract.payload.LicenseRestoredEvent;
import com.stove.common.kafka.contract.payload.LicenseSuspendedEvent;
import com.stove.common.messaging.ops.OutboxRepublishTargets;
import com.stove.common.messaging.outbox.OutboxEventRepository;
import com.stove.common.messaging.outbox.OutboxMetrics;
import com.stove.common.messaging.outbox.OutboxRelay;
import com.stove.order.api.listener.ApprovalCorrectionEventListener;
import com.stove.order.api.listener.LicenseEventListener;
import com.stove.order.api.listener.RefundRestorationEventListener;
import com.stove.order.api.listener.RefundSuspensionEventListener;
import com.stove.order.core.domain.Order;
import com.stove.order.core.domain.OrderRepository;
import com.stove.order.core.domain.OrderRequest;
import com.stove.order.core.domain.OrderStatus;
import com.stove.order.core.domain.PaymentResult;
import com.stove.order.core.domain.PublishedProduct;
import com.stove.order.core.domain.Refund;
import com.stove.order.core.domain.RefundApprovalStopRequest;
import com.stove.order.core.domain.RefundAttempt;
import com.stove.order.core.domain.RefundAttemptRepository;
import com.stove.order.core.domain.RefundFailureConfirmation;
import com.stove.order.core.domain.RefundRepository;
import com.stove.order.core.domain.RefundReviewDecision;
import com.stove.order.core.domain.RefundReviewRequest;
import com.stove.order.core.domain.RefundStatus;
import com.stove.order.core.service.ApplyApprovalCorrection;
import com.stove.order.core.service.ApplyPaymentResult;
import com.stove.order.core.service.ApplyRefundRestoration;
import com.stove.order.core.service.ApplyRefundSuspension;
import com.stove.order.core.service.DecideRefund;
import com.stove.order.core.service.RequestRefund;
import com.stove.order.core.service.RequestRefundApprovalStop;
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
import java.util.concurrent.atomic.AtomicLong;
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
import org.springframework.test.util.ReflectionTestUtils;
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
    private static final AtomicLong PRODUCTS = new AtomicLong(9000);
    @Autowired OrderRepository orders;
    @Autowired RefundRepository refunds;
    @Autowired ApplyPaymentResult apply;
    @Autowired RequestRefund request;
    @Autowired LicenseEventListener licenseEvents;
    @Autowired RefundSuspensionEventListener suspensionEvents;
    @Autowired RefundRestorationEventListener restorationEvents;
    @Autowired ApprovalCorrectionEventListener correctionEvents;
    @Autowired ApplyRefundRestoration restoration;
    @Autowired DecideRefund decide;
    @Autowired RequestRefundApprovalStop stop;
    @Autowired RefundAttemptRepository attempts;
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
        String other = insert(EventType.LICENSE_SUSPENDED, "SENT");

        assertThat(post("/api/v1/ops/outbox/" + dead + "/republish", "ADMIN", reason()).status())
                .as("DEAD 는 requeue 로 되살린다").isEqualTo(404);
        assertThat(post("/api/v1/ops/outbox/" + id() + "/republish", "ADMIN", reason()).status()).isEqualTo(404);
        assertThat(post("/api/v1/ops/outbox/" + other + "/republish", "ADMIN", reason()).status()).isEqualTo(400);
        assertThat(status(dead)).isEqualTo("DEAD");
        assertThat(status(other)).isEqualTo("SENT");
        assertThat(count("outbox_republish")).isZero();
    }

    /** 결정 129 범위 확장: Order는 자기가 소유한 정지·복구·승인 정정 요청만 재발행하고, License 결과 이벤트 행은 거절한다. */
    @Test
    void orderRepublishesOnlyItsOwnSuspensionRestorationAndCorrectionRequests() {
        assertThat(OutboxRepublishTargets.forOwner(OutboxRepublishTargets.ORDER)).contains(EventType.LICENSE_SUSPENSION_REQUESTED,
                EventType.LICENSE_RESTORATION_REQUESTED, EventType.LICENSE_APPROVAL_CORRECTION_REQUESTED);
        for (String type : List.of(EventType.LICENSE_SUSPENSION_REQUESTED, EventType.LICENSE_RESTORATION_REQUESTED,
                EventType.LICENSE_APPROVAL_CORRECTION_REQUESTED)) {
            String eventId = insert(type, "SENT");
            Response accepted = post("/api/v1/ops/outbox/" + eventId + "/republish", "ADMIN", reason());
            assertThat(accepted.status()).as(type).isEqualTo(200);
            assertThat(accepted.data().path("eventType").asText()).isEqualTo(type);
            assertThat(status(eventId)).isEqualTo("PENDING");
        }
        for (String type : OutboxRepublishTargets.forOwner(OutboxRepublishTargets.LICENSE)) {
            String eventId = insert(type, "SENT");
            assertThat(post("/api/v1/ops/outbox/" + eventId + "/republish", "ADMIN", reason()).status()).as(type).isEqualTo(400);
            assertThat(status(eventId)).isEqualTo("SENT");
        }
        assertThat(count("outbox_republish")).isEqualTo(3);
    }

    /** 미처리 LicenseSuspended 재발행은 PENDING_LICENSE_SUSPENSION을 PG 실행 요청으로 잇고, 이미 처리됐으면 효과가 없다. */
    @Test
    void aRepublishedSuspensionContinuesTheWaitingRefundToExecutionAndARepeatHasNoEffect() throws Exception {
        Suspending f = suspending();
        long before = count("outbox_event");

        suspensionEvents.receive(record(Topics.LICENSE, f.suspended()));

        assertThat(refundStatus(f.order())).isEqualTo(RefundStatus.PENDING_PAYMENT_REFUND);
        assertThat(jdbc.queryForList("SELECT event_type FROM outbox_event ORDER BY id", String.class))
                .as("정지 완료가 기존 PG 실행 요청으로 이어진다").hasSize((int) before + 1)
                .last().isEqualTo(EventType.REFUND_EXECUTION_REQUESTED);
        Map<String, List<Map<String, Object>>> afterFirst = snapshot();

        suspensionEvents.receive(record(Topics.LICENSE, f.suspended()));

        assertThat(snapshot()).as("이미 처리된 재발행은 받는 쪽에서 효과가 없다").isEqualTo(afterFirst);
    }

    /** 미처리 LicenseRestored 재발행은 PENDING_LICENSE_RESTORATION을 FAILED로 잇고, 이미 처리됐으면 효과가 없다. */
    @Test
    void aRepublishedRestorationContinuesTheFailedRefundToFailedAndARepeatHasNoEffect() throws Exception {
        Suspending f = suspending();
        suspensionEvents.receive(record(Topics.LICENSE, f.suspended()));
        RefundAttempt attempt = attempts.findByAttemptId(refund(f.order()).getCurrentAttemptId()).orElseThrow();
        restoration.execute(new RefundFailureConfirmation(id(), now(), attempt.getRefundId(), attempt.getAttemptId(),
                attempt.getExecutionRequestEventId(), f.order().getOrderNo(), f.order().getPaymentId(), f.order().getBuyerSubject(),
                f.order().getAmount(), f.order().getCurrency(), "pg-original", "cancel-own", "a".repeat(64)));
        assertThat(refundStatus(f.order())).isEqualTo(RefundStatus.PENDING_LICENSE_RESTORATION);
        LicenseRestorationRequestedEvent requested = payload(EventType.LICENSE_RESTORATION_REQUESTED, LicenseRestorationRequestedEvent.class);
        LicenseRestoredEvent restored = new LicenseRestoredEvent(id(), now().plusMillis(1), requested.eventId(), requested.refundId(),
                requested.attemptId(), requested.failureEventId(), requested.licenseId(), requested.orderNo(), requested.buyerSubject(),
                requested.productId(), requested.productCode(), requested.paymentId(), requested.suspensionRequestEventId(),
                requested.suspensionEventId(), false);

        restorationEvents.receive(record(Topics.LICENSE, restored));

        assertThat(refundStatus(f.order())).isEqualTo(RefundStatus.FAILED);
        Map<String, List<Map<String, Object>>> afterFirst = snapshot();

        restorationEvents.receive(record(Topics.LICENSE, restored));

        assertThat(snapshot()).as("이미 처리된 재발행은 받는 쪽에서 효과가 없다").isEqualTo(afterFirst);
    }

    /** 이미 처리된 LicenseApprovalCorrected 재발행은 받는 쪽에서 효과가 없다(정정 회차를 다시 열지 않는다). */
    @Test
    void aRepublishedApprovalCorrectionThatOrderAlreadyProcessedHasNoEffect() throws Exception {
        Order order = paidAt(Instant.now().minusSeconds(5 * 3600).truncatedTo(ChronoUnit.MICROS));
        LicenseGrantedEvent granted = granted(order);
        licenseEvents.receive(licenseGranted(granted));
        Refund pending = request.execute(order.getBuyerSubject(), order.getOrderNo());
        decide.execute(new RefundReviewRequest(id(), pending.getRefundId(), "usr_admin", RefundReviewDecision.APPROVED, "실행 불가 확인"));
        stop.execute(new RefundApprovalStopRequest(id(), pending.getRefundId(), pending.getRefundId(), "usr_admin", "자료를 잘못 읽어 재검토가 필요합니다"));
        assertThat(refundStatus(order)).isEqualTo(RefundStatus.PENDING_APPROVAL_STOP);
        Refund stopped = refund(order);
        suspensionEvents.receive(record(Topics.LICENSE, new LicenseSuspendedEvent(id(), now(), stopped.getSuspensionRequestEventId(),
                stopped.getRefundId(), granted.licenseId(), order.getOrderNo(), order.getBuyerSubject(), order.getProductId(),
                order.getProductCode(), order.getPaymentId())));
        LicenseApprovalCorrectionRequestedEvent requested = payload(EventType.LICENSE_APPROVAL_CORRECTION_REQUESTED,
                LicenseApprovalCorrectionRequestedEvent.class);
        LicenseApprovalCorrectedEvent corrected = new LicenseApprovalCorrectedEvent(id(), now().plusMillis(1), requested.eventId(),
                requested.refundId(), requested.reviewId(), requested.stopRequestId(), requested.licenseId(), requested.orderNo(),
                requested.buyerSubject(), requested.productId(), requested.productCode(), requested.paymentId(),
                requested.suspensionRequestEventId(), requested.suspensionEventId(), false);

        correctionEvents.receive(record(Topics.LICENSE, corrected));

        assertThat(refundStatus(order)).isEqualTo(RefundStatus.PENDING_REVIEW);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM processed_event WHERE consumer_group=?", Integer.class,
                ApplyApprovalCorrection.CONSUMER_GROUP)).isEqualTo(1);
        Map<String, List<Map<String, Object>>> afterFirst = snapshot();

        correctionEvents.receive(record(Topics.LICENSE, corrected));

        assertThat(snapshot()).as("이미 처리된 재발행은 받는 쪽에서 효과가 없다").isEqualTo(afterFirst);
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

    /** 지급 확인 뒤 자동 환불이 정지 요청을 내고 정지 완료를 기다리는 상태. */
    private Suspending suspending() throws Exception {
        Order paid = paid();
        LicenseGrantedEvent granted = granted(paid);
        licenseEvents.receive(licenseGranted(granted));
        request.execute(paid.getBuyerSubject(), paid.getOrderNo());
        Refund waiting = refund(paid);
        assertThat(waiting.getStatus()).isEqualTo(RefundStatus.PENDING_LICENSE_SUSPENSION);
        return new Suspending(paid, new LicenseSuspendedEvent(id(), now(), waiting.getSuspensionRequestEventId(), waiting.getRefundId(),
                granted.licenseId(), paid.getOrderNo(), paid.getBuyerSubject(), paid.getProductId(), paid.getProductCode(), paid.getPaymentId()));
    }

    private record Suspending(Order order, LicenseSuspendedEvent suspended) { }

    private LicenseGrantedEvent granted(Order paid) {
        return new LicenseGrantedEvent(id(), now(), id(), paid.getOrderNo(), paid.getBuyerSubject(), paid.getProductId(),
                paid.getProductCode(), paid.getPaymentId());
    }

    private Order paidAt(Instant resultAt) {
        String code = "Game-" + id();
        Order order = Order.create("usr_buyer", new OrderRequest(id(), code, 3, 10000),
                new PublishedProduct(PRODUCTS.incrementAndGet(), code, "재발행 검증 상품", 3, 10000, "KRW"), id());
        ReflectionTestUtils.setField(order, "createdAt", resultAt.minusSeconds(60));
        order.apply(new PaymentResult(id(), resultAt, order.getOrderNo(), order.getRequestEventId(), id(),
                order.getBuyerSubject(), order.getAmount(), "KRW", OrderStatus.PAID, null, null));
        return orders.saveAndFlush(order);
    }

    private <T> T payload(String type, Class<T> kind) throws Exception {
        return mapper.readValue(jdbc.queryForObject("SELECT payload FROM outbox_event WHERE event_type=?", String.class, type), kind);
    }

    private ConsumerRecord<String, String> record(String topic, DomainEvent event) throws Exception {
        String key = mapper.readTree(mapper.writeValueAsString(event)).path("orderNo").asText();
        ConsumerRecord<String, String> record = new ConsumerRecord<>(topic, 0, 0L, key, mapper.writeValueAsString(event));
        record.headers().add(EventHeaders.EVENT_ID, event.eventId().getBytes(StandardCharsets.UTF_8));
        record.headers().add(EventHeaders.EVENT_TYPE, event.eventType().getBytes(StandardCharsets.UTF_8));
        return record;
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    private Refund refund(Order paid) {
        return refunds.findByOrderNo(paid.getOrderNo()).orElseThrow();
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
        for (String table : List.of("purchase_order", "order_refund", "order_refund_review_round", "order_license_grant",
                "order_refund_attempt", "outbox_event", "processed_event")) {
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
