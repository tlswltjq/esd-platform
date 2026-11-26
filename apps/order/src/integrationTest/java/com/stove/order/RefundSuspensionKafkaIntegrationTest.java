package com.stove.order;

import org.springframework.dao.DataAccessResourceFailureException;
import static com.stove.order.OrderFixtures.id;
import static com.stove.order.OrderFixtures.order;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.contract.EventHeaders;
import com.stove.common.contract.payload.LicenseSuspendedEvent;
import com.stove.order.core.domain.LicenseGrant;
import com.stove.order.core.domain.LicenseSuspensionConfirmation;
import com.stove.order.core.domain.Order;
import com.stove.order.core.domain.OrderRepository;
import com.stove.order.core.domain.OrderStatus;
import com.stove.order.core.domain.PaymentResult;
import com.stove.order.core.domain.Refund;
import com.stove.order.core.domain.RefundAttemptRepository;
import com.stove.order.core.domain.RefundRepository;
import com.stove.order.core.domain.RefundStatus;
import com.stove.order.core.service.ApplyLicenseGrant;
import com.stove.order.core.service.ApplyRefundSuspension;
import com.stove.order.core.service.RequestRefund;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.GroupIdNotFoundException;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import com.stove.common.messaging.kafka.KeyQuarantine;
import org.apache.kafka.clients.consumer.ConsumerRecord;

class RefundSuspensionKafkaIntegrationTest extends OrderIntegrationTestBase {
    private static final Instant PAID_AT = Instant.parse("2026-10-26T01:00:00.123456Z");
    private static final Instant RECEIVED_AT = PAID_AT.plusSeconds(3 * 3600);
    private static final Instant STOPPED_AT = PAID_AT.plusSeconds(6 * 3600);
    @Autowired RequestRefund request;
    @Autowired ApplyLicenseGrant grant;
    @Autowired OrderRepository orders;
    @Autowired RefundRepository refunds;
    @Autowired RefundAttemptRepository attempts;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired KafkaListenerEndpointRegistry listeners;
    @MockitoBean Clock clock;
    @MockitoSpyBean ApplyRefundSuspension apply;
    // Boot @Bean의 반환 계약인 KafkaTemplate<?, ?>를 그대로 사용한다.
    @MockitoSpyBean(name = "kafkaTemplate") KafkaTemplate<?, ?> kafka;
    // 구현체(JpaKeyQuarantine)를 감싼다. 저장소는 인터페이스 프록시라 callRealMethod 를 쓸 수 없다.
    @MockitoSpyBean KeyQuarantine quarantined;

    @BeforeEach void initialize() { clear(); when(clock.instant()).thenReturn(RECEIVED_AT); }
    @AfterEach void clear() {
        listeners.stop();
        reset(kafka, apply);
        jdbc.update("DELETE FROM order_refund_attempt");
        jdbc.update("DELETE FROM order_license_grant");
        jdbc.update("DELETE FROM processed_event");
        jdbc.update("DELETE FROM order_refund");
        jdbc.update("DELETE FROM purchase_order");
        jdbc.update("DELETE FROM outbox_event");
    }

    @Test void duplicateKafkaDeliveriesCommitOneExecutionAndBothOffsets() throws Exception {
        Fixture fixture = fixture();
        listeners.start();
        send(fixture.event());
        RecordMetadata last = send(fixture.event());
        try (AdminClient admin = admin()) { awaitConsumed(admin, last); }
        assertThat(refunds.findByOrderNo(fixture.event().orderNo()).orElseThrow().getStatus()).isEqualTo(RefundStatus.PENDING_PAYMENT_REFUND);
        assertThat(attempts.count()).isEqualTo(1);
        assertThat(executionRequests()).isEqualTo(1);
        assertThat(inboxCount()).isEqualTo(1);
        verify(apply, times(2)).execute(fixture.input());
    }

    @Test void aCompletionWithNoRefundIsQuarantinedWithoutBusinessRetryOrReplacement() throws Exception {
        Fixture fixture = fixture();
        jdbc.update("DELETE FROM order_refund");
        try (AdminClient admin = admin()) {
            listeners.start();
            RecordMetadata sent = send(fixture.event());
            Map<String, Object> dead = receiveQuarantined(fixture.event());
            awaitConsumed(admin, sent);
            assertThat(dead.get("partition_key")).isEqualTo(fixture.event().orderNo());
            assertThat(dead.get("origin")).isEqualTo("partition=" + sent.partition() + " offset=" + sent.offset());
            assertThat(dead.get("payload")).isEqualTo(mapper.writeValueAsString(fixture.event()));
            verify(apply, times(1)).execute(fixture.input());
            assertThat(refunds.count()).isZero();
            assertThat(attempts.count()).isZero();
            assertThat(executionRequests()).isZero();
            assertThat(inboxCount()).isZero();
        }
    }

    @Test void aTransientDatabaseFailureRetriesTheSameCompletionAndCreatesOneExecution() throws Exception {
        Fixture fixture = fixture();
        doThrow(new TransientDataAccessResourceException("temporary database outage"))
                .doCallRealMethod().when(apply).execute(fixture.input());
        listeners.start();
        RecordMetadata sent = send(fixture.event());
        try (AdminClient admin = admin()) { awaitConsumed(admin, sent); }
        verify(apply, times(2)).execute(fixture.input());
        assertThat(attempts.count()).isEqualTo(1);
        assertThat(executionRequests()).isEqualTo(1);
        assertThat(inboxCount()).isEqualTo(1);
    }

    @Test void failedQuarantinePublicationKeepsTheSourceUncommittedUntilRecovery() throws Exception {
        Fixture fixture = fixture();
        jdbc.update("DELETE FROM order_refund");
        AtomicBoolean unavailable = new AtomicBoolean(true);
        AtomicInteger failedPublications = new AtomicInteger();
        failQuarantineRecording(quarantined, fixture.event().eventId(), unavailable, failedPublications);
        try (AdminClient admin = admin()) {
            listeners.start();
            RecordMetadata sent = send(fixture.event());
            Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                assertThat(failedPublications.get()).isPositive();
                assertThat(sourceOffset(admin, sent)).isLessThan(sent.offset() + 1);
            });
            assertThat(attempts.count()).isZero(); assertThat(executionRequests()).isZero();
            unavailable.set(false);
            receiveQuarantined(fixture.event());
            awaitConsumed(admin, sent);
            assertThat(inboxCount()).isZero();
            assertThat(refunds.count()).isZero();
        } finally { unavailable.set(false); }
    }

    private Fixture fixture() {
        Order paid = order();
        paid.apply(new PaymentResult(id(), PAID_AT, paid.getOrderNo(), paid.getRequestEventId(), id(),
                paid.getBuyerSubject(), paid.getAmount(), paid.getCurrency(), OrderStatus.PAID, null, null));
        orders.saveAndFlush(paid);
        LicenseGrant confirmed = new LicenseGrant(id(), PAID_AT.plusSeconds(60), id(), paid.getOrderNo(), paid.getBuyerSubject(),
                paid.getProductId(), paid.getProductCode(), paid.getPaymentId());
        grant.execute(confirmed);
        Refund refund = request.execute(paid.getBuyerSubject(), paid.getOrderNo());
        LicenseSuspendedEvent event = new LicenseSuspendedEvent(id(), STOPPED_AT, refund.getSuspensionRequestEventId(),
                refund.getRefundId(), confirmed.licenseId(), paid.getOrderNo(), paid.getBuyerSubject(), paid.getProductId(),
                paid.getProductCode(), paid.getPaymentId());
        LicenseSuspensionConfirmation input = new LicenseSuspensionConfirmation(event.eventId(), event.occurredAt(),
                event.requestEventId(), event.refundId(), event.licenseId(), event.orderNo(), event.buyerSubject(),
                event.productId(), event.productCode(), event.paymentId());
        when(clock.instant()).thenReturn(STOPPED_AT.plusSeconds(1));
        return new Fixture(event, input);
    }

    private RecordMetadata send(LicenseSuspendedEvent event) throws Exception {
        Message<String> message = MessageBuilder.withPayload(mapper.writeValueAsString(event))
                .setHeader(KafkaHeaders.TOPIC, event.topic()).setHeader(KafkaHeaders.KEY, event.partitionKey())
                .setHeader(EventHeaders.EVENT_ID, event.eventId().getBytes(StandardCharsets.UTF_8))
                .setHeader(EventHeaders.EVENT_TYPE, event.eventType().getBytes(StandardCharsets.UTF_8)).build();
        return kafka.send(message).get(10, TimeUnit.SECONDS).getRecordMetadata();
    }

    /** 격리 기록(소비 모듈 DB)이 안 되는 동안은 원본 오프셋을 넘기지 않아야 한다 — 잃지 않고 다시 받는다. */
    private static void failQuarantineRecording(KeyQuarantine quarantine, String eventId,
            AtomicBoolean unavailable, AtomicInteger failedRecordings) {
        doAnswer(call -> {
            ConsumerRecord<String, String> record = call.getArgument(1);
            if (eventId.equals(EventHeaders.lastValue(record.headers(), EventHeaders.EVENT_ID)) && unavailable.get()) {
                failedRecordings.incrementAndGet();
                throw new DataAccessResourceFailureException("quarantine table unavailable");
            }
            return call.callRealMethod();
        }).when(quarantine).quarantine(ArgumentMatchers.anyString(), ArgumentMatchers.any(ConsumerRecord.class), ArgumentMatchers.any());
    }

    private Map<String, Object> connectionProperties() {
        return Map.of("bootstrap.servers", kafka.getProducerFactory().getConfigurationProperties().get("bootstrap.servers"));
    }
    private AdminClient admin() { return AdminClient.create(connectionProperties()); }

    /** 결정 155·206: 정지 완료 원문이 Order DB 의 격리 표에 그 그룹·키로 보존된다. */
    private Map<String, Object> receiveQuarantined(LicenseSuspendedEvent event) {
        String query = "SELECT partition_key, origin, payload FROM consumer_quarantine WHERE event_id=? AND consumer_group=? AND status='HELD'";
        Awaitility.await().atMost(Duration.ofSeconds(40)).untilAsserted(() -> assertThat(
                jdbc.queryForList(query, event.eventId(), ApplyRefundSuspension.CONSUMER_GROUP)).hasSize(1));
        return jdbc.queryForMap(query, event.eventId(), ApplyRefundSuspension.CONSUMER_GROUP);
    }

    private void awaitConsumed(AdminClient admin, RecordMetadata record) {
        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(sourceOffset(admin, record)).isGreaterThanOrEqualTo(record.offset() + 1));
    }

    private long sourceOffset(AdminClient admin, RecordMetadata record) throws Exception {
        try {
            Map<TopicPartition, OffsetAndMetadata> offsets = admin.listConsumerGroupOffsets(ApplyRefundSuspension.CONSUMER_GROUP)
                    .partitionsToOffsetAndMetadata().get(10, TimeUnit.SECONDS);
            OffsetAndMetadata committed = offsets.get(new TopicPartition(record.topic(), record.partition()));
            return committed == null ? -1 : committed.offset();
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof GroupIdNotFoundException) return -1;
            throw failure;
        }
    }

    private int executionRequests() { return jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE event_type='RefundExecutionRequested'", Integer.class); }
    private int inboxCount() { return jdbc.queryForObject("SELECT COUNT(*) FROM processed_event WHERE consumer_group=?", Integer.class, ApplyRefundSuspension.CONSUMER_GROUP); }
    private record Fixture(LicenseSuspendedEvent event, LicenseSuspensionConfirmation input) {}
}
