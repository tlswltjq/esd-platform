package com.stove.order;

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
import com.stove.common.kafka.EventHeaders;
import com.stove.common.kafka.contract.Topics;
import com.stove.common.kafka.contract.payload.RefundPaymentConfirmedEvent;
import com.stove.common.kafka.contract.payload.RefundExecutionSucceededEvent;
import com.stove.common.kafka.contract.payload.RefundExecutionFailedEvent;
import com.stove.common.kafka.contract.payload.LicenseRestoredEvent;
import com.stove.common.kafka.contract.payload.LicenseRestorationRequestedEvent;
import com.stove.common.kafka.contract.payload.LicenseSuspendedEvent;
import com.stove.order.core.domain.RefundRetryRequest;
import com.stove.order.core.domain.RefundRetryReceipt;
import com.stove.order.core.service.RequestRefundRetry;
import com.stove.common.kafka.contract.DomainEvent;
import com.stove.order.core.domain.LicenseGrant;
import com.stove.order.core.domain.RefundPaymentConfirmation;
import com.stove.order.core.domain.Order;
import com.stove.order.core.domain.OrderRepository;
import com.stove.order.core.domain.OrderStatus;
import com.stove.order.core.domain.PaymentResult;
import com.stove.order.core.domain.Refund;
import com.stove.order.core.domain.RefundAttemptRepository;
import com.stove.order.core.domain.RefundRepository;
import com.stove.order.core.domain.RefundStatus;
import com.stove.order.core.domain.LicenseSuspensionConfirmation;
import com.stove.order.core.domain.RefundAttempt;
import com.stove.order.core.domain.RefundAttemptStatus;
import com.stove.order.core.service.ApplyRefundSuspension;
import com.stove.order.core.service.ApplyLicenseGrant;
import com.stove.order.core.service.ApplyRefundPaymentConfirmation;
import com.stove.order.core.service.RequestRefund;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.GroupIdNotFoundException;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.support.SendResult;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

class RefundPaymentConfirmationKafkaIntegrationTest extends OrderIntegrationTestBase {
    private static final Instant PAID_AT = Instant.parse("2026-10-26T01:00:00.123456Z");
    private static final Instant RECEIVED_AT = PAID_AT.plusSeconds(3 * 3600);
    private static final Instant STOPPED_AT = PAID_AT.plusSeconds(6 * 3600);
    @Autowired RequestRefund request;
    @Autowired RequestRefundRetry retry;
    @Autowired ApplyLicenseGrant grant;
    @Autowired ApplyRefundSuspension suspend;
    @Autowired OrderRepository orders;
    @Autowired RefundRepository refunds;
    @Autowired RefundAttemptRepository attempts;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired KafkaListenerEndpointRegistry listeners;
    @MockitoBean Clock clock;
    @MockitoSpyBean ApplyRefundPaymentConfirmation apply;
    // Boot @Bean의 반환 계약인 KafkaTemplate<?, ?>를 그대로 사용한다.
    @MockitoSpyBean(name = "kafkaTemplate") KafkaTemplate<?, ?> kafka;

    @BeforeEach void initialize() { clear(); when(clock.instant()).thenReturn(RECEIVED_AT); }
    @AfterEach void clear() {
        listeners.stop();
        reset(kafka, apply);
        jdbc.update("DELETE FROM order_refund_attempt ORDER BY id DESC");
        jdbc.update("DELETE FROM order_license_grant");
        jdbc.update("DELETE FROM processed_event");
        jdbc.update("DELETE FROM order_refund");
        jdbc.update("DELETE FROM purchase_order");
        jdbc.update("DELETE FROM outbox_event");
    }

    @Test void duplicateKafkaConfirmationsCompleteTheRefundButDoNotInventExecutionSuccess() throws Exception {
        Fixture fixture = fixture();
        listeners.start();
        send(fixture.event());
        RecordMetadata last = send(fixture.event());
        try (AdminClient admin = admin()) { awaitConsumed(admin, last); }
        assertThat(refunds.findByOrderNo(fixture.event().orderNo()).orElseThrow().getStatus()).isEqualTo(RefundStatus.COMPLETED);
        assertThat(attempts.count()).isEqualTo(1);
        assertThat(executionRequests()).isEqualTo(1);
        assertThat(inboxCount()).isEqualTo(1);
        verify(apply, times(2)).execute(fixture.input());
        Refund completed = refunds.findByOrderNo(fixture.event().orderNo()).orElseThrow();
        RefundAttempt attempt = attempts.findByAttemptId(completed.getCurrentAttemptId()).orElseThrow();
        assertThat(completed.getPaymentConfirmationSource()).isEqualTo("PG_QUERY_UNATTRIBUTED");
        assertThat(completed.getCompletedAt()).isEqualTo(fixture.event().occurredAt());
        assertThat(attempt.getStatus()).isEqualTo(RefundAttemptStatus.PENDING);
        assertThat(attempt.getResolvedAt()).isNull();
        assertThat(attempt.isReconciliationRequired()).isTrue();
        assertThat(orders.findByOrderNoAndBuyerSubject(completed.getOrderNo(), fixture.event().buyerSubject()).orElseThrow().getStatus()).isEqualTo(OrderStatus.PAID);
    }

    @Test void aCompletionWithNoRefundIsQuarantinedWithoutBusinessRetryOrReplacement() throws Exception {
        Fixture fixture = fixture();
        jdbc.update("DELETE FROM order_refund_attempt ORDER BY id DESC");
        jdbc.update("DELETE FROM order_refund");
        try (KafkaConsumer<String, String> consumer = dltConsumer(); AdminClient admin = admin()) {
            listeners.start();
            RecordMetadata sent = send(fixture.event());
            ConsumerRecord<String, String> dead = receiveDlt(consumer, fixture.event());
            awaitConsumed(admin, sent);
            assertThat(dead.key()).isEqualTo(fixture.event().orderNo());
            assertThat(dead.partition()).isEqualTo(sent.partition());
            assertThat(dead.value()).isEqualTo(mapper.writeValueAsString(fixture.event()));
            verify(apply, times(1)).execute(fixture.input());
            assertThat(refunds.count()).isZero();
            assertThat(attempts.count()).isZero();
            assertThat(executionRequests()).isEqualTo(1);
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
        Refund completed = refunds.findByOrderNo(fixture.event().orderNo()).orElseThrow();
        RefundAttempt attempt = attempts.findByAttemptId(completed.getCurrentAttemptId()).orElseThrow();
        assertThat(completed.getPaymentConfirmationSource()).isEqualTo("PG_QUERY_UNATTRIBUTED");
        assertThat(completed.getCompletedAt()).isEqualTo(fixture.event().occurredAt());
        assertThat(attempt.getStatus()).isEqualTo(RefundAttemptStatus.PENDING);
        assertThat(attempt.getResolvedAt()).isNull();
        assertThat(attempt.isReconciliationRequired()).isTrue();
        assertThat(orders.findByOrderNoAndBuyerSubject(completed.getOrderNo(), fixture.event().buyerSubject()).orElseThrow().getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(attempts.count()).isEqualTo(1);
        assertThat(executionRequests()).isEqualTo(1);
        assertThat(inboxCount()).isEqualTo(1);
    }

    @Test void failedQuarantinePublicationKeepsTheSourceUncommittedUntilRecovery() throws Exception {
        Fixture fixture = fixture();
        jdbc.update("DELETE FROM order_refund_attempt ORDER BY id DESC");
        jdbc.update("DELETE FROM order_refund");
        AtomicBoolean unavailable = new AtomicBoolean(true);
        AtomicInteger failedPublications = new AtomicInteger();
        failQuarantinePublication(kafka, fixture.event().eventId(), unavailable, failedPublications);
        try (KafkaConsumer<String, String> consumer = dltConsumer(); AdminClient admin = admin()) {
            listeners.start();
            RecordMetadata sent = send(fixture.event());
            Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                assertThat(failedPublications.get()).isPositive();
                assertThat(sourceOffset(admin, sent)).isLessThan(sent.offset() + 1);
            });
            assertThat(attempts.count()).isZero(); assertThat(executionRequests()).isEqualTo(1);
            unavailable.set(false);
            receiveDlt(consumer, fixture.event());
            awaitConsumed(admin, sent);
            assertThat(inboxCount()).isZero();
            assertThat(refunds.count()).isZero();
        } finally { unavailable.set(false); }
    }

    @Test void realKafkaFailureRestorationAndExplicitRetryRequireANewSeparateSuspension() throws Exception {
        Fixture fixture = fixture(); RefundPaymentConfirmedEvent purchase = fixture.event();
        when(clock.instant()).thenReturn(STOPPED_AT.plusSeconds(5));
        RefundExecutionFailedEvent failed = new RefundExecutionFailedEvent(id(), STOPPED_AT.plusSeconds(2), purchase.refundId(), purchase.attemptId(),
                purchase.requestEventId(), purchase.orderNo(), purchase.paymentId(), purchase.buyerSubject(), purchase.amount(), purchase.currency(),
                "pg-original", "cancel-own", "a".repeat(64));
        listeners.start(); send(failed); send(failed);
        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(refunds.findByOrderNo(purchase.orderNo()).orElseThrow().getStatus()).isEqualTo(RefundStatus.PENDING_LICENSE_RESTORATION);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE event_type='LicenseRestorationRequested'", Integer.class)).isEqualTo(1);
        });
        LicenseRestorationRequestedEvent requested = mapper.readValue(jdbc.queryForObject("SELECT payload FROM outbox_event WHERE event_type='LicenseRestorationRequested'", String.class), LicenseRestorationRequestedEvent.class);
        LicenseRestoredEvent restored = new LicenseRestoredEvent(id(), STOPPED_AT.plusSeconds(6), requested.eventId(), requested.refundId(), requested.attemptId(),
                requested.failureEventId(), requested.licenseId(), requested.orderNo(), requested.buyerSubject(), requested.productId(), requested.productCode(),
                requested.paymentId(), requested.suspensionRequestEventId(), requested.suspensionEventId());
        send(restored); send(restored); send(failed);
        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(refunds.findByOrderNo(purchase.orderNo()).orElseThrow().getStatus()).isEqualTo(RefundStatus.FAILED);
            assertThat(attempts.findByAttemptId(purchase.attemptId()).orElseThrow().getRestoredEventId()).isEqualTo(restored.eventId());
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM processed_event WHERE consumer_group='order-refund-restoration'", Integer.class)).isEqualTo(2);
        });
        assertThat(executionRequests()).isEqualTo(1);
        when(clock.instant()).thenReturn(STOPPED_AT.plusSeconds(86400));
        RefundRetryReceipt receipt = retry.execute(new RefundRetryRequest(purchase.buyerSubject(), purchase.orderNo(), purchase.attemptId()));
        assertThat(executionRequests()).isEqualTo(1);
        LicenseSuspendedEvent nextStop = new LicenseSuspendedEvent(id(), STOPPED_AT.plusSeconds(86401), receipt.suspensionRequestEventId(),
                receipt.refundId(), requested.licenseId(), purchase.orderNo(), purchase.buyerSubject(), requested.productId(), requested.productCode(), purchase.paymentId());
        when(clock.instant()).thenReturn(STOPPED_AT.plusSeconds(86402)); send(nextStop); send(nextStop); send(failed); send(restored);
        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            Refund current = refunds.findByOrderNo(purchase.orderNo()).orElseThrow();
            RefundAttempt next = attempts.findByAttemptId(current.getCurrentAttemptId()).orElseThrow();
            assertThat(current.getStatus()).isEqualTo(RefundStatus.PENDING_PAYMENT_REFUND);
            assertThat(next.getAttemptNo()).isEqualTo(2); assertThat(next.getPreviousAttemptId()).isEqualTo(purchase.attemptId());
            assertThat(executionRequests()).isEqualTo(2); assertThat(attempts.count()).isEqualTo(2);
        });
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
        LicenseSuspensionConfirmation suspension = new LicenseSuspensionConfirmation(id(), STOPPED_AT,
                refund.getSuspensionRequestEventId(), refund.getRefundId(), confirmed.licenseId(), paid.getOrderNo(),
                paid.getBuyerSubject(), paid.getProductId(), paid.getProductCode(), paid.getPaymentId());
        when(clock.instant()).thenReturn(STOPPED_AT.plusSeconds(1));
        suspend.execute(suspension);
        Refund current = refunds.findByOrderNo(paid.getOrderNo()).orElseThrow();
        RefundAttempt attempt = attempts.findByAttemptId(current.getCurrentAttemptId()).orElseThrow();
        RefundPaymentConfirmedEvent event = new RefundPaymentConfirmedEvent(id(), STOPPED_AT.plusSeconds(2), refund.getRefundId(),
                attempt.getAttemptId(), attempt.getExecutionRequestEventId(), paid.getOrderNo(), paid.getPaymentId(),
                paid.getBuyerSubject(), paid.getAmount(), paid.getCurrency(), "PG_QUERY_UNATTRIBUTED", "pg-original", List.of("cancel-1"));
        RefundPaymentConfirmation input = new RefundPaymentConfirmation(event.eventId(), event.occurredAt(), event.refundId(),
                event.attemptId(), event.requestEventId(), event.orderNo(), event.paymentId(), event.buyerSubject(), event.amount(),
                event.currency(), event.source(), event.providerTransactionId(), event.cancellationIds());
        return new Fixture(event, input);
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void lateSuccessAndMoneyConfirmationCanArriveInEitherOrder(boolean successFirst) throws Exception {
        Fixture fixture = fixture(); RefundPaymentConfirmedEvent money = fixture.event();
        RefundExecutionSucceededEvent success = new RefundExecutionSucceededEvent(id(), money.occurredAt().plusSeconds(1), money.eventId(),
                money.refundId(), money.attemptId(), money.requestEventId(), money.orderNo(), money.paymentId(), money.buyerSubject(),
                money.amount(), money.currency(), money.providerTransactionId(), money.cancellationIds().get(0));
        listeners.start();
        send(successFirst ? success : money);
        RecordMetadata last = send(successFirst ? money : success);
        try (AdminClient admin = admin()) { awaitConsumed(admin, last); }
        Refund completed = refunds.findByOrderNo(money.orderNo()).orElseThrow();
        RefundAttempt attempt = attempts.findByAttemptId(money.attemptId()).orElseThrow();
        assertThat(completed.getPaymentConfirmationEventId()).isEqualTo(money.eventId());
        assertThat(completed.getPaymentConfirmationSource()).isEqualTo("PG_QUERY_UNATTRIBUTED");
        assertThat(completed.getCompletedAt()).isEqualTo(money.occurredAt());
        assertThat(attempt.getStatus()).isEqualTo(RefundAttemptStatus.SUCCEEDED);
        assertThat(attempt.getSuccessEventId()).isEqualTo(success.eventId());
        assertThat(attempt.isReconciliationRequired()).isFalse();
        RecordMetadata duplicate = send(success);
        try (AdminClient admin = admin()) { awaitConsumed(admin, duplicate); }
        assertThat(inboxCount()).isEqualTo(2);
    }

    @Test void directCompletionFromKafkaAlsoConfirmsTheExecutionInTheSameOrderTransaction() throws Exception {
        Fixture fixture = fixture(); RefundPaymentConfirmedEvent money = fixture.event();
        RefundPaymentConfirmedEvent response = new RefundPaymentConfirmedEvent(money.eventId(), money.occurredAt(), money.refundId(),
                money.attemptId(), money.requestEventId(), money.orderNo(), money.paymentId(), money.buyerSubject(), money.amount(), money.currency(),
                "PG_RESPONSE", money.providerTransactionId(), money.cancellationIds());
        listeners.start(); RecordMetadata last = send(response);
        try (AdminClient admin = admin()) { awaitConsumed(admin, last); }
        assertThat(refunds.findByOrderNo(money.orderNo()).orElseThrow().getStatus()).isEqualTo(RefundStatus.COMPLETED);
        RefundAttempt attempt = attempts.findByAttemptId(money.attemptId()).orElseThrow();
        assertThat(attempt.getStatus()).isEqualTo(RefundAttemptStatus.SUCCEEDED);
        assertThat(attempt.getSuccessEventId()).isEqualTo(money.eventId()); assertThat(attempt.isReconciliationRequired()).isFalse();
        assertThat(inboxCount()).isEqualTo(1);
    }

    private RecordMetadata send(DomainEvent event) throws Exception {
        Message<String> message = MessageBuilder.withPayload(mapper.writeValueAsString(event))
                .setHeader(KafkaHeaders.TOPIC, event.topic()).setHeader(KafkaHeaders.KEY, event.partitionKey())
                .setHeader(EventHeaders.EVENT_ID, event.eventId().getBytes(StandardCharsets.UTF_8))
                .setHeader(EventHeaders.EVENT_TYPE, event.eventType().getBytes(StandardCharsets.UTF_8)).build();
        return kafka.send(message).get(10, TimeUnit.SECONDS).getRecordMetadata();
    }

    private static <K, V> void failQuarantinePublication(KafkaTemplate<K, V> template, String eventId,
            AtomicBoolean unavailable, AtomicInteger failedPublications) {
        doAnswer(call -> {
            ProducerRecord<K, V> record = call.getArgument(0);
            if (record.topic().equals(Topics.PAYMENT + ".DLT") && record.value() instanceof String payload
                    && payload.contains(eventId) && unavailable.get()) {
                failedPublications.incrementAndGet();
                return CompletableFuture.<SendResult<K, V>>failedFuture(new KafkaException("DLT producer unavailable"));
            }
            return call.callRealMethod();
        }).when(template).send(ArgumentMatchers.<ProducerRecord<K, V>>any());
    }

    private Map<String, Object> connectionProperties() {
        return Map.of("bootstrap.servers", kafka.getProducerFactory().getConfigurationProperties().get("bootstrap.servers"));
    }
    private AdminClient admin() { return AdminClient.create(connectionProperties()); }

    private KafkaConsumer<String, String> dltConsumer() {
        HashMap<String, Object> properties = new HashMap<>();
        properties.putAll(connectionProperties());
        properties.put("group.id", "refund-completion-dlt-" + id());
        properties.put("auto.offset.reset", "earliest");
        properties.put("enable.auto.commit", false);
        properties.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        properties.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(properties);
        consumer.subscribe(List.of(Topics.PAYMENT + ".DLT"));
        return consumer;
    }

    private ConsumerRecord<String, String> receiveDlt(KafkaConsumer<String, String> consumer, RefundPaymentConfirmedEvent event) {
        long deadline = System.nanoTime() + Duration.ofSeconds(40).toNanos();
        while (System.nanoTime() < deadline) {
            for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(300))) {
                if (record.value().contains(event.eventId()) && record.headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_CONSUMER_GROUP) != null
                        && new String(record.headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_CONSUMER_GROUP).value(), StandardCharsets.UTF_8)
                                .equals(ApplyRefundPaymentConfirmation.CONSUMER_GROUP)) return record;
            }
        }
        throw new AssertionError("전액 취소 확인 원문이 DLT에 보존되지 않았습니다: " + event.eventId());
    }

    private void awaitConsumed(AdminClient admin, RecordMetadata record) {
        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(sourceOffset(admin, record)).isGreaterThanOrEqualTo(record.offset() + 1));
    }

    private long sourceOffset(AdminClient admin, RecordMetadata record) throws Exception {
        try {
            Map<TopicPartition, OffsetAndMetadata> offsets = admin.listConsumerGroupOffsets(ApplyRefundPaymentConfirmation.CONSUMER_GROUP)
                    .partitionsToOffsetAndMetadata().get(10, TimeUnit.SECONDS);
            OffsetAndMetadata committed = offsets.get(new TopicPartition(record.topic(), record.partition()));
            return committed == null ? -1 : committed.offset();
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof GroupIdNotFoundException) return -1;
            throw failure;
        }
    }

    private int executionRequests() { return jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE event_type='RefundExecutionRequested'", Integer.class); }
    private int inboxCount() { return jdbc.queryForObject("SELECT COUNT(*) FROM processed_event WHERE consumer_group=?", Integer.class, ApplyRefundPaymentConfirmation.CONSUMER_GROUP); }
    private record Fixture(RefundPaymentConfirmedEvent event, RefundPaymentConfirmation input) {}
}
