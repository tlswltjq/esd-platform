package com.stove.payment;

import static com.stove.payment.PaymentFixtures.id;
import static com.stove.payment.PaymentFixtures.request;
import static com.stove.payment.PortOneFixtures.CHANNEL;
import static com.stove.payment.PortOneFixtures.STORE;
import static com.stove.payment.RefundFixtures.execution;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.when;

import com.stove.common.messaging.outbox.OutboxRecorder;
import com.stove.common.testcontainers.SharedContainers;
import com.stove.payment.adapter.pg.JdbcPortOneWebhookReceipts;
import com.stove.payment.adapter.pg.PortOneRefundPaymentLookup;
import com.stove.payment.core.domain.Payment;
import com.stove.payment.core.domain.PaymentRepository;
import com.stove.payment.core.domain.PgResult;
import com.stove.payment.core.domain.PortOneWebhookNotice;
import com.stove.payment.core.domain.RefundExecution;
import com.stove.payment.core.domain.RefundExecutionRepository;
import com.stove.payment.core.domain.RefundExecutionStatus;
import com.stove.payment.core.domain.RefundLookupClaim;
import com.stove.payment.core.service.ReceivePortOneWebhook;
import com.stove.payment.core.service.ReceiveRefundExecution;
import com.stove.payment.core.service.ReconcileRefundPayment;
import com.stove.payment.core.service.RecordRefundCancellationPreparation;
import com.stove.payment.core.service.RecordRefundPreparation;
import com.stove.payment.core.service.ResolveRefundLookup;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.MySQLContainer;

class RefundLookupIntegrationTest extends PaymentIntegrationTestBase {
    private static final Instant BASE = Payment.now();
    @Autowired PaymentRepository payments;
    @Autowired RefundExecutionRepository executions;
    @Autowired ReceiveRefundExecution receive;
    @Autowired PlatformTransactionManager transactions;
    @Autowired OutboxRecorder outbox;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean Clock clock;
    private RefundExecution current;
    private HttpServer pg;
    private ExecutorService threads;
    private final AtomicInteger reads = new AtomicInteger();
    private final AtomicInteger writes = new AtomicInteger();
    private final AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
    private final CountDownLatch entered = new CountDownLatch(1);
    private final CountDownLatch released = new CountDownLatch(1);
    private volatile boolean complete;
    private volatile boolean unavailable;
    private volatile boolean blocked;

    @BeforeEach void initialize() throws Exception {
        clear(); at(0);
        pg = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        threads = Executors.newFixedThreadPool(4); pg.setExecutor(threads);
        pg.createContext("/payments/", exchange -> {
            try {
                if (!"GET".equals(exchange.getRequestMethod())) { writes.incrementAndGet(); exchange.sendResponseHeaders(405, -1); return; }
                reads.incrementAndGet();
                assertThat(stored().getLookupSchedule().getClaimId()).isNotNull();
                entered.countDown();
                if (blocked) assertThat(released.await(15, TimeUnit.SECONDS)).isTrue();
                byte[] bytes = body().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(unavailable ? 503 : 200, bytes.length);
                try (OutputStream output = exchange.getResponseBody()) { output.write(bytes); }
            } catch (Throwable failure) { callbackFailure.compareAndSet(null, failure); }
            finally { exchange.close(); }
        });
        pg.start();
    }

    @AfterEach void close() {
        released.countDown(); if (pg != null) pg.stop(0); if (threads != null) threads.shutdownNow();
        clear();
    }

    private void clear() {
        jdbc.update("DELETE FROM refund_cancellation_evidence"); jdbc.update("DELETE FROM refund_execution_trace");
        jdbc.update("DELETE FROM payment_pg_webhook"); jdbc.update("DELETE FROM refund_execution");
        jdbc.update("DELETE FROM purchase_payment"); jdbc.update("DELETE FROM processed_event"); jdbc.update("DELETE FROM outbox_event");
    }

    @Test void onlyPreparedRequestsAreCheckedAtThePersistedBackoffDeadlinesWithoutPosting() {
        received(); ResolveRefundLookup worker = worker(Runnable::run);
        at(100); worker.execute(); assertThat(reads.get()).isZero();
        at(0); prepare();
        for (long second : new long[] {4, 5, 14, 15, 34, 35, 94, 95}) { at(second); worker.execute(); }
        assertThat(reads.get()).isEqualTo(4); assertThat(writes.get()).isZero();
        assertThat(stored().getLookupSchedule().getNextAt()).isEqualTo(BASE.plusSeconds(155));
        assertThat(stored().getStatus()).isEqualTo(RefundExecutionStatus.PENDING);
        assertThat(callbackFailure.get()).isNull();
    }

    @Test void verifiedFullCancellationStopsTheScheduleAndKeepsTheOriginalPurchasePaid() {
        received(); prepare(); complete = true; at(5); worker(Runnable::run).execute();
        assertThat(stored().getRefundConfirmedAt()).isEqualTo(BASE.plusSeconds(5));
        assertThat(stored().getLookupSchedule().getNextAt()).isNull();
        assertThat(stored().getLookupSchedule().getClaimId()).isNull();
        assertThat(stored().getStatus()).isEqualTo(RefundExecutionStatus.PENDING);
        assertThat(payments.findByPaymentId(current.getPaymentId()).orElseThrow().getStatus().name()).isEqualTo("PAID");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE event_type<>'RefundExecutionPrepared'", Integer.class)).isEqualTo(1);
        at(1000); worker(Runnable::run).execute(); assertThat(reads.get()).isEqualTo(1); assertThat(writes.get()).isZero();
        assertThat(callbackFailure.get()).isNull();
    }

    @Test void anUnavailableProviderSpendsOneCheckAndDoesNotDeclareFailureOrRestoreALicense() {
        received(); prepare(); unavailable = true; at(5); worker(Runnable::run).execute();
        assertThat(stored().getLookupSchedule().getNextAt()).isEqualTo(BASE.plusSeconds(15));
        assertThat(stored().getLookupSchedule().getClaimId()).isNull();
        assertThat(stored().getRefundConfirmedAt()).isNull(); assertThat(stored().getStatus()).isEqualTo(RefundExecutionStatus.PENDING);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE event_type<>'RefundExecutionPrepared'", Integer.class)).isZero();
    }

    @Test void aNewProcessResumesAfterTheThreeHourPostDeadlineWithoutSendingACancellation() {
        received(); prepare(); at(14400); prepare();
        assertThat(stored().getAutomaticCancellationStoppedAt()).isNotNull();
        worker(Runnable::run).execute();
        assertThat(reads.get()).isEqualTo(1); assertThat(writes.get()).isZero();
        assertThat(stored().getLookupSchedule().getNextAt()).isEqualTo(BASE.plusSeconds(14460));
    }

    @Test void twoScannersShareOneCommittedLeaseAndWebhookDuringHttpIsNotLost() throws Exception {
        received(); prepare(); at(5); blocked = true;
        ExecutorService callers = Executors.newFixedThreadPool(2);
        ExecutorService http = Executors.newFixedThreadPool(2);
        try {
            ResolveRefundLookup first = worker(http); ResolveRefundLookup second = worker(http);
            List<Future<?>> scans = List.of(callers.submit(first::execute), callers.submit(second::execute));
            for (Future<?> scan : scans) scan.get(10, TimeUnit.SECONDS);
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(reads.get()).isEqualTo(1);
            at(6); wake("msg_during_http");
            assertThat(stored().getLookupSchedule().getWakeAt()).isEqualTo(BASE.plusSeconds(6));
            at(8); released.countDown();
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(stored().getLookupSchedule().getClaimId()).isNull());
            worker(Runnable::run).execute();
            assertThat(reads.get()).isEqualTo(2); assertThat(stored().getLookupSchedule().getNextAt()).isEqualTo(BASE.plusSeconds(15));
            assertThat(callbackFailure.get()).isNull(); assertThat(writes.get()).isZero();
        } finally { released.countDown(); callers.shutdownNow(); http.shutdownNow(); }
    }

    @Test void exactWebhookRedeliveryDoesNotWakeAgainOrResetThePeriodicDeadline() {
        received(); prepare(); at(1); wake("msg_once"); worker(Runnable::run).execute();
        assertThat(reads.get()).isEqualTo(1);
        at(2); wake("msg_once"); worker(Runnable::run).execute();
        assertThat(reads.get()).isEqualTo(1);
        assertThat(stored().getLookupSchedule().getNextAt()).isEqualTo(BASE.plusSeconds(5));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_pg_webhook", Integer.class)).isEqualTo(1);
    }

    @Test void rejectedSubmissionReleasesTheLeaseWithoutAdvancingTheBackoff() {
        received(); prepare(); at(5);
        worker(task -> { throw new RejectedExecutionException("fixture capacity"); }).execute();
        assertThat(stored().getLookupSchedule().getClaimId()).isNull(); assertThat(stored().getLookupSchedule().getStage()).isZero();
        assertThat(reads.get()).isZero(); worker(Runnable::run).execute(); assertThat(reads.get()).isEqualTo(1);
        assertThat(stored().getLookupSchedule().getNextAt()).isEqualTo(BASE.plusSeconds(15));
    }

    @Test void aCrashedWorkersLeaseExpiresAndItsLateFinishCannotOverwriteTheNewLease() {
        received(); prepare(); at(5);
        List<Runnable> abandoned = new CopyOnWriteArrayList<>(); worker(abandoned::add).execute();
        String oldToken = stored().getLookupSchedule().getClaimId();
        at(101); assertThat(executions.findDueLookups(clock.instant(), PageRequest.of(0, 20))).isEmpty();
        at(102); worker(Runnable::run).execute();
        assertThat(reads.get()).isEqualTo(1);
        new TransactionTemplate(transactions).executeWithoutResult(tx -> executions.lockByAttemptId(current.getAttemptId()).orElseThrow()
                .getLookupSchedule().finish(new RefundLookupClaim(oldToken, BASE.plusSeconds(5)), BASE, BASE.plusSeconds(103)));
        assertThat(stored().getLookupSchedule().getNextAt()).isEqualTo(BASE.plusSeconds(162));
    }

    @Test void wakeAndReceiptAreRolledBackTogetherAfterAnActualDatabaseFlush() {
        received(); prepare(); at(1);
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            receiver().execute(notice("msg_rollback")); executions.flush();
            throw new IllegalStateException("after durable receipt and wake flush");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(stored().getLookupSchedule().getWakeAt()).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_pg_webhook", Integer.class)).isZero();
        wake("msg_rollback"); assertThat(stored().getLookupSchedule().getWakeAt()).isEqualTo(BASE.plusSeconds(1));
    }

    @Test void simulatedProviderRowsAreExcludedAndInvalidLeaseStateIsRejectedByTheDatabase() {
        received(); prepare(); at(5);
        jdbc.update("UPDATE purchase_payment SET provider='SIMULATED',next_failure_lookup_at=NULL WHERE payment_id=?", current.getPaymentId());
        worker(Runnable::run).execute(); assertThat(reads.get()).isZero();
        assertThatThrownBy(() -> jdbc.update("UPDATE refund_execution SET refund_lookup_claim_id=? WHERE attempt_id=?",
                id(), current.getAttemptId())).isInstanceOf(DataAccessException.class).hasMessageContaining("ck_refund_lookup_lease");
        assertThatThrownBy(() -> jdbc.update("UPDATE refund_execution SET refund_lookup_stage=4 WHERE attempt_id=?",
                current.getAttemptId())).isInstanceOf(DataAccessException.class).hasMessageContaining("ck_refund_lookup_stage");
    }

    @Test void migrationFromV19BackfillsTheOriginalPreparationAnchorWithoutChangingTheReceipt() {
        received(); prepare();
        MySQLContainer<?> mysql = SharedContainers.MYSQL;
        String schema = "refund_lookup_" + UUID.randomUUID().toString().replace("-", "");
        String server = "jdbc:mysql://" + mysql.getHost() + ":" + mysql.getMappedPort(3306) + "/";
        String options = "?useSSL=false&allowPublicKeyRetrieval=true";
        JdbcTemplate root = new JdbcTemplate(new DriverManagerDataSource(server + mysql.getDatabaseName() + options, "root", mysql.getPassword()));
        root.execute("CREATE DATABASE " + schema);
        try {
            DriverManagerDataSource source = new DriverManagerDataSource(server + schema + options, "root", mysql.getPassword());
            JdbcTemplate legacy = new JdbcTemplate(source);
            String location = "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath();
            Flyway.configure().dataSource(source).locations(location).target("19").load().migrate();
            String paymentColumns = String.join(",", legacy.queryForList("SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=? AND TABLE_NAME='purchase_payment' AND GENERATION_EXPRESSION='' ORDER BY ORDINAL_POSITION", String.class, schema));
            legacy.update("INSERT INTO purchase_payment(" + paymentColumns + ") SELECT " + paymentColumns + " FROM " + mysql.getDatabaseName() + ".purchase_payment WHERE payment_id=?", current.getPaymentId());
            String columns = "attempt_id,refund_id,request_event_id,request_occurred_at,order_no,payment_id,buyer_subject,amount,currency,license_suspension_event_id,received_at,status,first_cancel_prepared_at";
            legacy.update("INSERT INTO refund_execution(" + columns + ") SELECT " + columns + " FROM " + mysql.getDatabaseName()
                    + ".refund_execution WHERE attempt_id=?", current.getAttemptId());
            Flyway.configure().dataSource(source).locations(location).load().migrate();
            assertThat(legacy.queryForObject("SELECT next_refund_lookup_at FROM refund_execution", Timestamp.class).toInstant()).isEqualTo(BASE.plusSeconds(5));
            assertThat(legacy.queryForObject("SELECT first_cancel_prepared_at FROM refund_execution", Timestamp.class).toInstant()).isEqualTo(BASE);
            assertThat(legacy.queryForObject("SELECT status FROM refund_execution", String.class)).isEqualTo("PENDING");
            assertThat(legacy.queryForObject("SELECT refund_lookup_stage FROM refund_execution", Integer.class)).isZero();
        } finally { root.execute("DROP DATABASE " + schema); }
    }

    private ResolveRefundLookup worker(Executor executor) {
        PortOneRefundPaymentLookup lookup = new PortOneRefundPaymentLookup(RestClient.builder()
                .baseUrl("http://127.0.0.1:" + pg.getAddress().getPort()).build(), STORE, CHANNEL);
        ReconcileRefundPayment reconcile = new ReconcileRefundPayment(payments, executions, purchase -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return lookup.find(purchase);
        }, outbox, transactions, clock);
        return new ResolveRefundLookup(payments, executions, reconcile, transactions, clock, executor, Duration.ofSeconds(97), 20);
    }
    private void received() {
        Payment paid = Payment.receive(request(), "PORTONE_TEST");
        paid.confirm(new PgResult("PORTONE_TEST", paid.getPaymentId(), paid.getOrderNo(), paid.getAmount(), "KRW", "pg-original",
                BASE.minusSeconds(60), PgResult.Decision.APPROVED, null, null, null), id(), BASE.minusSeconds(30));
        payments.saveAndFlush(paid); current = receive.execute(execution(paid));
    }
    private void prepare() { new RecordRefundCancellationPreparation(executions, transactions, clock, new RecordRefundPreparation(outbox)).execute(current.getAttemptId()); }
    private RefundExecution stored() { return executions.findByAttemptId(current.getAttemptId()).orElseThrow(); }
    private void at(long seconds) { when(clock.instant()).thenReturn(BASE.plusSeconds(seconds)); }
    private void wake(String messageId) { new TransactionTemplate(transactions).executeWithoutResult(tx -> receiver().execute(notice(messageId))); }
    private ReceivePortOneWebhook receiver() { return new ReceivePortOneWebhook(payments, new JdbcPortOneWebhookReceipts(jdbc), clock, executions); }
    private PortOneWebhookNotice notice(String messageId) {
        return new PortOneWebhookNotice(messageId, "a".repeat(64), "Transaction.Cancelled", STORE,
                current.getPaymentId(), "pg-original", BASE);
    }
    private String body() {
        long cancelled = complete ? current.getAmount() : 0;
        String cancellations = complete ? "[{\"status\":\"SUCCEEDED\",\"id\":\"cancel-1\",\"totalAmount\":" + cancelled
                + ",\"cancelledAt\":\"" + BASE + "\"}]" : "[]";
        return """
                {"status":"%s","id":"%s","storeId":"%s","version":"V2","currency":"KRW",
                "channel":{"type":"TEST","key":"%s","pgProvider":"TOSSPAY_V2"},
                "amount":{"total":%d,"paid":%d,"discount":0,"cancelled":%d},"cancellations":%s,
                "customData":"{\\"orderNo\\":\\"%s\\"}","paidAt":"%s","pgTxId":"pg-original","cancelledAt":"%s"}
                """.formatted(complete ? "CANCELLED" : "PAID", current.getPaymentId(), STORE, CHANNEL, current.getAmount(),
                        current.getAmount(), cancelled, cancellations, current.getOrderNo(), BASE.minusSeconds(60), BASE);
    }
}
