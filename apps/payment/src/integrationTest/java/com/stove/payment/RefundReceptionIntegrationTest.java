package com.stove.payment;

import static com.stove.payment.PaymentFixtures.approved;
import static com.stove.payment.PaymentFixtures.id;
import static com.stove.payment.PaymentFixtures.request;
import static com.stove.payment.RefundFixtures.event;
import static com.stove.payment.RefundFixtures.execution;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.kafka.EventHeaders;
import com.stove.common.kafka.contract.EventType;
import com.stove.common.kafka.contract.Topics;
import com.stove.common.kafka.contract.payload.RefundExecutionRequestedEvent;
import com.stove.common.messaging.inbox.ProcessedEventGuard;
import com.stove.common.web.error.BusinessException;
import com.stove.payment.adapter.pg.SimulatedPgHttpGateway;
import com.stove.payment.core.domain.Payment;
import com.stove.payment.core.domain.PaymentRepository;
import com.stove.payment.core.domain.PaymentStatus;
import com.stove.payment.core.domain.PaymentTraceContext;
import com.stove.payment.core.domain.PgResult;
import com.stove.payment.core.domain.RefundExecution;
import com.stove.payment.core.domain.RefundExecutionRepository;
import com.stove.payment.core.domain.RefundExecutionRequest;
import com.stove.payment.core.domain.RefundExecutionStatus;
import com.stove.payment.core.port.PaymentTraceContexts;
import com.stove.payment.core.port.RefundExecutionTraceContexts;
import com.stove.payment.core.service.ReceiveOrderPayment;
import com.stove.payment.core.service.ReceiveRefundExecution;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.GroupIdNotFoundException;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class RefundReceptionIntegrationTest extends PaymentIntegrationTestBase {
    private static final Instant RECEIVED_AT = Instant.parse("2026-10-27T01:01:00.123456Z");
    private static final PaymentTraceContext PURCHASE_PARENT = new PaymentTraceContext("00-11111111111111111111111111111111-2222222222222222-01");
    private static final PaymentTraceContext REFUND_PARENT = new PaymentTraceContext("00-33333333333333333333333333333333-4444444444444444-01");
    @Autowired ReceiveOrderPayment orderReceipt;
    @Autowired ReceiveRefundExecution receive;
    @Autowired PaymentRepository payments;
    @Autowired RefundExecutionRepository executions;
    @Autowired ProcessedEventGuard inbox;
    @Autowired PaymentTraceContexts paymentContexts;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired PlatformTransactionManager transactions;
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired KafkaListenerEndpointRegistry listeners;
    @MockitoBean Clock clock;
    @MockitoSpyBean RefundExecutionTraceContexts contexts;
    @MockitoSpyBean SimulatedPgHttpGateway gateway;

    @BeforeEach void initialize() { clear(); when(clock.instant()).thenReturn(RECEIVED_AT); }
    @AfterEach void clear() {
        listeners.stop();
        reset(contexts, gateway);
        jdbc.update("DELETE FROM refund_execution");
        jdbc.update("DELETE FROM purchase_payment");
        jdbc.update("DELETE FROM processed_event");
        jdbc.update("DELETE FROM outbox_event");
    }

    @Test void receiptAndInboxPersistWithoutChangingThePurchaseOrCallingTheSupplier() {
        Payment paid = paid();
        PgResult original = paid.supplierResult();
        RefundExecutionRequest input = execution(paid);
        receive.execute(input, REFUND_PARENT);

        RefundExecution stored = executions.findByAttemptId(input.attemptId()).orElseThrow();
        assertThat(stored.request()).isEqualTo(input);
        assertThat(stored.getReceivedAt()).isEqualTo(RECEIVED_AT);
        assertThat(stored.getStatus()).isEqualTo(RefundExecutionStatus.PENDING);
        assertThat(inboxCount()).isEqualTo(1);
        assertThat(contexts.find(input.attemptId())).isEqualTo(REFUND_PARENT);
        assertThat(paymentContexts.find(paid.getPaymentId())).isEqualTo(PURCHASE_PARENT);
        when(clock.instant()).thenReturn(RECEIVED_AT.plusSeconds(3600));
        receive.execute(input, PaymentTraceContext.NONE);
        assertThat(executions.count()).isEqualTo(1);
        assertThat(inboxCount()).isEqualTo(1);
        assertThat(contexts.find(input.attemptId())).isEqualTo(REFUND_PARENT);
        assertThat(executions.findByAttemptId(input.attemptId()).orElseThrow().getReceivedAt()).isEqualTo(RECEIVED_AT);
        Payment unchanged = payments.findById(paid.getId()).orElseThrow();
        assertThat(unchanged.getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(unchanged.supplierResult()).isEqualTo(original);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event", Integer.class)).isZero();
        verifyNoInteractions(gateway);
    }

    @Test void aStoppedExternalProviderDoesNotPreventDurableReceipt() {
        Payment paid = paid();
        RefundExecutionRequest input = execution(paid);
        try {
            ExternalPgProcess.stop();
            receive.execute(input);
            assertThat(executions.findByAttemptId(input.attemptId()).orElseThrow().getStatus()).isEqualTo(RefundExecutionStatus.PENDING);
            assertThat(inboxCount()).isEqualTo(1);
            verifyNoInteractions(gateway);
        } finally { ExternalPgProcess.restart("APPROVED", 300); }
    }

    @Test void failureAfterTraceInsertRollsBackTheReceiptInboxAndParentTogether() {
        Payment paid = paid();
        RefundExecutionRequest input = execution(paid);
        try {
            doAnswer(call -> { call.callRealMethod(); throw new IllegalStateException("abort after refund trace insert"); })
                    .when(contexts).save(anyString(), any(PaymentTraceContext.class), any(Instant.class));
            assertThatThrownBy(() -> receive.execute(input, REFUND_PARENT)).isInstanceOf(IllegalStateException.class);
            assertThat(executions.count()).isZero();
            assertThat(inboxCount()).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refund_execution_trace", Integer.class)).isZero();
        } finally { reset(contexts); }
        receive.execute(input, REFUND_PARENT);
        assertThat(executions.count()).isEqualTo(1); assertThat(inboxCount()).isEqualTo(1);
        assertThat(contexts.find(input.attemptId())).isEqualTo(REFUND_PARENT);
        verifyNoInteractions(gateway);
    }

    @Test void concurrentDuplicateReceiptsSerializeOnTheConfirmedPayment() throws Exception {
        Payment paid = paid();
        RefundExecutionRequest input = execution(paid);
        CountDownLatch written = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = pool.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(transaction -> {
                receive.execute(input, REFUND_PARENT); written.countDown(); await(commit);
            }));
            await(written);
            Future<?> second = pool.submit(() -> { started.countDown(); receive.execute(input); });
            await(started);
            assertThatThrownBy(() -> second.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            commit.countDown(); first.get(10, TimeUnit.SECONDS); second.get(10, TimeUnit.SECONDS);
            assertThat(executions.count()).isEqualTo(1); assertThat(inboxCount()).isEqualTo(1);
            assertThat(contexts.find(input.attemptId())).isEqualTo(REFUND_PARENT);
        } finally {
            commit.countDown(); pool.shutdownNow(); assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test void aNewAttemptCannotReplaceThePendingExecution() {
        Payment paid = paid();
        RefundExecutionRequest first = execution(paid);
        receive.execute(first);
        RefundExecutionRequest next = new RefundExecutionRequest(id(), first.occurredAt(), first.refundId(), id(), first.orderNo(),
                first.paymentId(), first.buyerSubject(), first.amount(), first.currency(), id());
        assertThatThrownBy(() -> receive.execute(next)).isInstanceOf(BusinessException.class);
        assertThat(executions.count()).isEqualTo(1); assertThat(inboxCount()).isEqualTo(1);
        assertThat(executions.findByAttemptId(next.attemptId())).isEmpty();
        verifyNoInteractions(gateway);
    }

    @Test void mismatchedAmountsAndChangedDuplicatesCannotAlterTheOriginalReceipt() {
        Payment paid = paid();
        RefundExecutionRequest input = execution(paid);
        RefundExecutionRequest partial = new RefundExecutionRequest(input.eventId(), input.occurredAt(), input.refundId(), input.attemptId(),
                input.orderNo(), input.paymentId(), input.buyerSubject(), 9000, input.currency(), input.licenseSuspensionEventId());
        assertThatThrownBy(() -> receive.execute(partial)).isInstanceOf(BusinessException.class);
        assertThat(executions.count()).isZero(); assertThat(inboxCount()).isZero();
        receive.execute(input, REFUND_PARENT);
        RefundExecutionRequest changed = new RefundExecutionRequest(input.eventId(), input.occurredAt(), input.refundId(), input.attemptId(),
                input.orderNo(), input.paymentId(), input.buyerSubject(), input.amount(), input.currency(), id());
        assertThatThrownBy(() -> receive.execute(changed)).isInstanceOf(BusinessException.class);
        assertThat(executions.findByAttemptId(input.attemptId()).orElseThrow().request()).isEqualTo(input);
        assertThat(executions.count()).isEqualTo(1); assertThat(inboxCount()).isEqualTo(1);
    }

    @Test void inconsistentInboxAndReceiptCannotBeSilentlyRepaired() {
        Payment paid = paid();
        RefundExecutionRequest input = execution(paid);
        receive.execute(input);
        jdbc.update("DELETE FROM processed_event WHERE consumer_group=?", ReceiveRefundExecution.CONSUMER_GROUP);
        assertThatThrownBy(() -> receive.execute(input)).isInstanceOf(BusinessException.class);
        assertThat(inboxCount()).isZero();
        assertThat(executions.count()).isEqualTo(1);
        jdbc.update("DELETE FROM refund_execution");
        new TransactionTemplate(transactions).executeWithoutResult(transaction ->
                inbox.firstDelivery(input.eventId(), ReceiveRefundExecution.CONSUMER_GROUP, EventType.REFUND_EXECUTION_REQUESTED));
        assertThatThrownBy(() -> receive.execute(input)).isInstanceOf(BusinessException.class);
        assertThat(executions.count()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refund_execution_trace", Integer.class)).isZero();
    }

    @ParameterizedTest @ValueSource(strings = {"attempt", "event", "payment", "refund"})
    void databaseConstraintsProtectIdentitiesAndPendingPaymentOrRefundBindings(String collision) {
        Payment first = paid();
        Payment second = collision.equals("payment") ? first : paid();
        RefundExecutionRequest a = execution(first);
        executions.saveAndFlush(RefundExecution.receive(first, a, RECEIVED_AT));
        RefundExecutionRequest b = new RefundExecutionRequest(collision.equals("event") ? a.eventId() : id(), a.occurredAt(),
                collision.equals("refund") ? a.refundId() : id(), collision.equals("attempt") ? a.attemptId() : id(), second.getOrderNo(),
                second.getPaymentId(), second.getBuyerSubject(), second.getAmount(), second.getCurrency(), id());
        assertThatThrownBy(() -> executions.saveAndFlush(RefundExecution.receive(second, b, RECEIVED_AT)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(executions.count()).isEqualTo(1);
    }

    @Test void theDatabaseRejectsAReceiptWithoutItsOriginalPayment() {
        Payment detached = RefundFixtures.paid();
        RefundExecution receipt = RefundExecution.receive(detached, execution(detached), RECEIVED_AT);
        assertThatThrownBy(() -> executions.saveAndFlush(receipt)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(executions.count()).isZero();
    }

    @Test void kafkaDuplicatesPreserveTheFirstParentAndCommitBothDeliveries() throws Exception {
        Payment paid = paid();
        RefundExecutionRequest input = execution(paid);
        RefundExecutionRequestedEvent event = event(input);
        listeners.start();
        send(event, REFUND_PARENT);
        RecordMetadata last = send(event, PURCHASE_PARENT);
        awaitConsumed(last);
        assertThat(executions.count()).isEqualTo(1); assertThat(inboxCount()).isEqualTo(1);
        assertThat(executions.findByAttemptId(input.attemptId()).orElseThrow().request()).isEqualTo(input);
        assertThat(contexts.find(input.attemptId())).isEqualTo(REFUND_PARENT);
        assertThat(paymentContexts.find(paid.getPaymentId())).isEqualTo(PURCHASE_PARENT);
        verifyNoInteractions(gateway);
    }

    @Test void invalidFinancialFactsReachDltWithoutCreatingARefundOrCallingPg() throws Exception {
        Payment paid = paid();
        RefundExecutionRequest input = execution(paid);
        RefundExecutionRequestedEvent invalid = new RefundExecutionRequestedEvent(input.eventId(), input.occurredAt(), input.refundId(),
                input.attemptId(), input.orderNo(), input.paymentId(), input.buyerSubject(), 9000, input.currency(), input.licenseSuspensionEventId());
        HashMap<String, Object> properties = new HashMap<>();
        properties.putAll(connectionProperties()); properties.put("group.id", "refund-receipt-dlt-" + id());
        properties.put("auto.offset.reset", "earliest"); properties.put("enable.auto.commit", false);
        properties.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        properties.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(properties)) {
            consumer.subscribe(List.of(Topics.ORDER + ".DLT")); listeners.start();
            RecordMetadata sent = send(invalid, REFUND_PARENT);
            ConsumerRecord<String, String> found = null;
            long deadline = System.nanoTime() + Duration.ofSeconds(40).toNanos();
            while (found == null && System.nanoTime() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(300))) {
                    if (record.value().contains(invalid.eventId()) && record.headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_CONSUMER_GROUP) != null
                            && new String(record.headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_CONSUMER_GROUP).value(), StandardCharsets.UTF_8)
                                    .equals(ReceiveRefundExecution.CONSUMER_GROUP)) found = record;
                }
            }
            assertThat(found).isNotNull();
            assertThat(found.value()).isEqualTo(mapper.writeValueAsString(invalid));
            assertThat(found.key()).isEqualTo(invalid.orderNo());
            awaitConsumed(sent);
            assertThat(executions.count()).isZero(); assertThat(inboxCount()).isZero();
            assertThat(payments.findById(paid.getId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.PAID);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event", Integer.class)).isZero();
            verifyNoInteractions(gateway);
        }
    }

    private Payment paid() {
        Payment received = orderReceipt.execute(request(), PURCHASE_PARENT);
        new TransactionTemplate(transactions).executeWithoutResult(transaction -> {
            Payment current = payments.lockByPaymentId(received.getPaymentId()).orElseThrow();
            current.confirm(approved(current), id(), Payment.now());
        });
        return payments.findById(received.getId()).orElseThrow();
    }

    private RecordMetadata send(RefundExecutionRequestedEvent event, PaymentTraceContext parent) throws Exception {
        ProducerRecord<String, String> record = new ProducerRecord<>(event.topic(), event.partitionKey(), mapper.writeValueAsString(event));
        record.headers().add(EventHeaders.EVENT_ID, event.eventId().getBytes(StandardCharsets.UTF_8));
        record.headers().add(EventHeaders.EVENT_TYPE, event.eventType().getBytes(StandardCharsets.UTF_8));
        record.headers().add(EventHeaders.TRACE_PARENT, parent.traceParent().getBytes(StandardCharsets.UTF_8));
        return kafka.send(record).get(10, TimeUnit.SECONDS).getRecordMetadata();
    }

    private Map<String, Object> connectionProperties() {
        return Map.of("bootstrap.servers", kafka.getProducerFactory().getConfigurationProperties().get("bootstrap.servers"));
    }

    private void awaitConsumed(RecordMetadata record) {
        try (AdminClient admin = AdminClient.create(connectionProperties())) {
            Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                Map<TopicPartition, OffsetAndMetadata> offsets;
                try {
                    offsets = admin.listConsumerGroupOffsets(ReceiveRefundExecution.CONSUMER_GROUP)
                            .partitionsToOffsetAndMetadata().get(10, TimeUnit.SECONDS);
                } catch (ExecutionException failure) {
                    if (failure.getCause() instanceof GroupIdNotFoundException) {
                        throw new AssertionError("환불 접수 소비 그룹의 시작을 기다립니다.", failure);
                    }
                    throw failure;
                }
                OffsetAndMetadata committed = offsets.get(new TopicPartition(record.topic(), record.partition()));
                assertThat(committed).isNotNull();
                assertThat(committed.offset()).isGreaterThanOrEqualTo(record.offset() + 1);
            });
        }
    }

    private int inboxCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM processed_event WHERE consumer_group=?", Integer.class, ReceiveRefundExecution.CONSUMER_GROUP);
    }
    private static void await(CountDownLatch latch) {
        try { assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue(); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
    }
}
