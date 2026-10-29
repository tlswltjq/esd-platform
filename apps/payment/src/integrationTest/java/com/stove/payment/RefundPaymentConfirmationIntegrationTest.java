package com.stove.payment;

import static com.stove.payment.PaymentFixtures.id;
import static com.stove.payment.PaymentFixtures.request;
import static com.stove.payment.PortOneFixtures.CHANNEL;
import static com.stove.payment.PortOneFixtures.STORE;
import static com.stove.payment.RefundFixtures.execution;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.stove.common.kafka.contract.DomainEvent;
import com.stove.common.kafka.contract.payload.RefundPaymentConfirmedEvent;
import com.stove.common.messaging.outbox.OutboxRecorder;
import com.stove.payment.adapter.pg.PortOneRefundPaymentLookup;
import com.stove.payment.core.domain.Payment;
import com.stove.payment.core.domain.PaymentRepository;
import com.stove.payment.core.domain.PgResult;
import com.stove.payment.core.domain.PgUnavailable;
import com.stove.payment.core.domain.RefundExecution;
import com.stove.payment.core.domain.RefundExecutionRepository;
import com.stove.payment.core.domain.RefundExecutionStatus;
import com.stove.payment.core.service.ReceiveRefundExecution;
import com.stove.payment.core.service.ReconcileRefundPayment;
import com.stove.payment.core.service.RecordRefundCancellationPreparation;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

class RefundPaymentConfirmationIntegrationTest extends PaymentIntegrationTestBase {
    private static final Instant BASE = Payment.now();
    @Autowired PaymentRepository payments;
    @Autowired RefundExecutionRepository executions;
    @Autowired ReceiveRefundExecution receive;
    @Autowired RecordRefundCancellationPreparation prepare;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean Clock clock;
    @MockitoSpyBean OutboxRecorder outbox;
    private HttpServer pg;
    private String response;
    private int responseStatus;
    private final AtomicInteger reads = new AtomicInteger();
    private final AtomicInteger writes = new AtomicInteger();

    @BeforeEach void initialize() throws Exception {
        clear(); when(clock.instant()).thenReturn(BASE);
        responseStatus = 200;
        pg = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        pg.createContext("/payments/", exchange -> {
            if ("GET".equals(exchange.getRequestMethod())) reads.incrementAndGet(); else writes.incrementAndGet();
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseStatus, bytes.length);
            try (OutputStream output = exchange.getResponseBody()) { output.write(bytes); }
        });
        pg.start();
    }

    @AfterEach void clear() {
        if (pg != null) { pg.stop(0); pg = null; }
        reset(outbox); reads.set(0); writes.set(0);
        jdbc.update("DELETE FROM payment_pg_webhook"); jdbc.update("DELETE FROM refund_execution");
        jdbc.update("DELETE FROM payment_order_lifecycle"); jdbc.update("DELETE FROM purchase_payment"); jdbc.update("DELETE FROM processed_event"); jdbc.update("DELETE FROM outbox_event");
    }

    @Test void expiredExecutionCanConfirmMoneyAndPublishOnceWithoutClaimingItsOwnSuccess() throws Exception {
        RefundExecution received = received();
        PgResult original = payments.findByPaymentId(received.getPaymentId()).orElseThrow().supplierResult();
        prepare.execute(received.getAttemptId());
        when(clock.instant()).thenReturn(BASE.plusSeconds(10800)); prepare.execute(received.getAttemptId());
        when(clock.instant()).thenReturn(BASE.plusSeconds(10801));
        service().execute(received.getAttemptId());
        RefundExecution confirmed = stored(received);
        assertThat(confirmed.getRefundConfirmedAt()).isEqualTo(BASE.plusSeconds(10801));
        assertThat(confirmed.getStatus()).isEqualTo(RefundExecutionStatus.PENDING);
        assertThat(confirmed.getAutomaticCancellationStoppedAt()).isEqualTo(BASE.plusSeconds(10800));
        assertThat(confirmed.getConfirmedCancellations()).hasSize(1);
        assertThat(payments.findByPaymentId(received.getPaymentId()).orElseThrow().supplierResult()).isEqualTo(original);
        RefundPaymentConfirmedEvent event = event();
        assertThat(event.source()).isEqualTo("PG_QUERY_UNATTRIBUTED");
        assertThat(event.requestEventId()).isEqualTo(received.getRequestEventId());
        assertThat(event.cancellationIds()).containsExactly("cancel-1");
        assertThat(event.eventId()).isEqualTo(confirmed.getRefundConfirmationEventId());
        assertThat(jdbc.queryForMap("SELECT topic,partition_key FROM outbox_event WHERE event_type='RefundPaymentConfirmed'"))
                .containsEntry("topic", "stove.payment.v1").containsEntry("partition_key", received.getOrderNo());
        service().execute(received.getAttemptId());
        assertThat(stored(received).getRefundConfirmationEventId()).isEqualTo(event.eventId());
        assertThat(count("outbox_event")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE event_type='RefundExecutionPrepared'",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE event_type='RefundPaymentConfirmed'",Integer.class)).isEqualTo(1);
        assertThat(reads.get()).isEqualTo(1); assertThat(writes.get()).isZero();
        assertThatThrownBy(() -> prepare.execute(received.getAttemptId())).isInstanceOf(RuntimeException.class);
    }

    @Test void failureAfterOutboxInsertionRollsBackEvidenceAndAllowsReadOnlyRecovery() {
        RefundExecution received = received();
        new TransactionTemplate(transactions).executeWithoutResult(tx ->
                doAnswer(call -> { call.callRealMethod(); throw new IllegalStateException("after outbox insert"); })
                        .when(outbox).record(anyString(), anyString(), any(DomainEvent.class)));
        assertThatThrownBy(() -> service().execute(received.getAttemptId())).isInstanceOf(IllegalStateException.class);
        assertThat(stored(received).getRefundConfirmedAt()).isNull();
        assertThat(count("refund_cancellation_evidence")).isZero(); assertThat(count("outbox_event")).isZero();
        reset(outbox); service().execute(received.getAttemptId());
        assertThat(count("refund_cancellation_evidence")).isEqualTo(1); assertThat(count("outbox_event")).isEqualTo(1);
        assertThat(writes.get()).isZero();
    }

    @Test void concurrentLookupsCommitOneEvidenceSetAndOneNotification() throws Exception {
        RefundExecution received = received();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> work = List.of(pool.submit(() -> { start.await(); service().execute(received.getAttemptId()); return null; }),
                    pool.submit(() -> { start.await(); service().execute(received.getAttemptId()); return null; }));
            start.countDown();
            for (Future<?> future : work) future.get(15, TimeUnit.SECONDS);
            assertThat(count("refund_cancellation_evidence")).isEqualTo(1); assertThat(count("outbox_event")).isEqualTo(1);
            assertThat(writes.get()).isZero();
        } finally { pool.shutdownNow(); }
    }

    @Test void unavailableRequestedOrMismatchedEvidenceCannotCompleteOrFailTheRefund() {
        RefundExecution received = received(); String valid = response;
        responseStatus = 503;
        assertThatThrownBy(() -> service().execute(received.getAttemptId())).isInstanceOf(PgUnavailable.class);
        responseStatus = 200; response = valid.replace("CANCELLED", "PARTIAL_CANCELLED");
        service().execute(received.getAttemptId());
        response = valid.replace("SUCCEEDED", "REQUESTED");
        assertThatThrownBy(() -> service().execute(received.getAttemptId())).isInstanceOf(IllegalArgumentException.class);
        response = valid.replace("pg-original", "other-transaction");
        assertThatThrownBy(() -> service().execute(received.getAttemptId())).isInstanceOf(IllegalArgumentException.class);
        assertThat(stored(received).getRefundConfirmedAt()).isNull();
        assertThat(stored(received).getStatus()).isEqualTo(RefundExecutionStatus.PENDING);
        assertThat(count("outbox_event")).isZero(); assertThat(count("refund_cancellation_evidence")).isZero();
        assertThat(writes.get()).isZero();
    }

    private RefundExecution received() {
        Payment payment = Payment.receive(request(), "PORTONE_TEST");
        payment.confirm(new PgResult("PORTONE_TEST", payment.getPaymentId(), payment.getOrderNo(), payment.getAmount(), "KRW",
                "pg-original", BASE.minusSeconds(60), PgResult.Decision.APPROVED, null, null, null), id(), BASE.minusSeconds(30));
        payments.saveAndFlush(payment);
        response = """
                {"status":"CANCELLED","id":"%s","storeId":"%s","version":"V2","currency":"KRW",
                "channel":{"type":"TEST","key":"%s","pgProvider":"TOSSPAY_V2"},
                "amount":{"total":%d,"paid":%d,"discount":0,"cancelled":%d},"customData":"{\\"orderNo\\":\\"%s\\"}",
                "paidAt":"%s","pgTxId":"pg-original","cancelledAt":"%s",
                "cancellations":[{"status":"SUCCEEDED","id":"cancel-1","totalAmount":%d,"cancelledAt":"%s"}]}
                """.formatted(payment.getPaymentId(), STORE, CHANNEL, payment.getAmount(), payment.getAmount(), payment.getAmount(),
                        payment.getOrderNo(), BASE.minusSeconds(60), BASE.minusSeconds(1), payment.getAmount(), BASE.minusSeconds(1));
        return receive.execute(execution(payment));
    }
    private ReconcileRefundPayment service() {
        return new ReconcileRefundPayment(payments, executions, new PortOneRefundPaymentLookup(RestClient.builder()
                .baseUrl("http://127.0.0.1:" + pg.getAddress().getPort()).build(), STORE, CHANNEL), outbox, transactions, clock);
    }
    private RefundExecution stored(RefundExecution received) { return executions.findByAttemptId(received.getAttemptId()).orElseThrow(); }
    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
    private RefundPaymentConfirmedEvent event() throws Exception {
        return new ObjectMapper().registerModule(new JavaTimeModule()).readValue(jdbc.queryForObject(
                "SELECT payload FROM outbox_event WHERE event_type='RefundPaymentConfirmed'", String.class), RefundPaymentConfirmedEvent.class);
    }
}
