package com.stove.payment;

import static com.stove.payment.PaymentFixtures.id;
import static com.stove.payment.PaymentFixtures.request;
import static com.stove.payment.RefundFixtures.execution;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.testcontainers.SharedContainers;
import com.stove.payment.core.domain.Payment;
import com.stove.payment.core.domain.PaymentRepository;
import com.stove.payment.core.domain.PgRefundCancellationReceipt;
import com.stove.payment.core.domain.PgRefundCancellationSuccess;
import com.stove.payment.core.domain.PgRefundConfirmation;
import com.stove.payment.core.domain.PgRefundFailureConfirmation;
import com.stove.payment.core.domain.PgRefundLookupRequest;
import com.stove.payment.core.domain.PgResult;
import com.stove.payment.core.domain.RefundCancellation;
import com.stove.payment.core.domain.RefundCancellationClaim;
import com.stove.payment.core.domain.RefundCancellationHold;
import com.stove.payment.core.domain.RefundCancellationHoldRepository;
import com.stove.payment.core.domain.RefundExecution;
import com.stove.payment.core.domain.RefundExecutionRepository;
import com.stove.payment.core.domain.RefundExecutionRequest;
import com.stove.payment.core.domain.RefundExecutionStatus;
import com.stove.payment.core.domain.RefundInvestigation;
import com.stove.payment.core.domain.RefundInvestigationRepository;
import com.stove.payment.core.service.ReceiveRefundExecution;
import com.stove.payment.core.service.RecordRefundCancellationPreparation;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.MySQLContainer;

/** Real HTTP, JWT and MySQL. Supplier and License completion facts remain contract fixtures. */
class RefundInvestigationIntegrationTest extends PaymentIntegrationTestBase {
    private static final String ROOT = "/api/v1/admin/refund-delays";
    private static final Instant BASE = Payment.now();
    private static final Instant DELAY = BASE.plusSeconds(10800);
    @MockitoSpyBean PaymentRepository payments;
    @Autowired RefundExecutionRepository executions;
    @Autowired RefundCancellationHoldRepository holds;
    @MockitoSpyBean RefundInvestigationRepository investigations;
    @Autowired ReceiveRefundExecution receive;
    @Autowired RecordRefundCancellationPreparation prepare;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired EntityManager entityManager;
    @MockitoBean Clock clock;
    @LocalServerPort int port;

    @BeforeEach @AfterEach void clear() {
        reset(payments, investigations); when(clock.instant()).thenReturn(BASE);
        jdbc.update("DELETE FROM payment_pg_webhook"); jdbc.update("DELETE FROM refund_execution ORDER BY id DESC");
        jdbc.update("DELETE FROM payment_order_lifecycle"); jdbc.update("DELETE FROM purchase_payment"); jdbc.update("DELETE FROM processed_event"); jdbc.update("DELETE FROM outbox_event");
    }

    @Test void exactBoundarySelectsOnlyPreparedUnresolvedPortOneAttemptsAndReadsDoNotWrite() {
        RefundExecution first = prepared(); RefundExecution second = prepared();
        when(clock.instant()).thenReturn(BASE); RefundExecution unprepared = receive.execute(execution(paid("PORTONE_TEST")));
        RefundExecution simulated = receive.execute(execution(paid("SIMULATED"))); prepare.execute(simulated.getAttemptId());
        when(clock.instant()).thenReturn(BASE.plusNanos(1000)); RefundExecution newer = receive.execute(execution(paid("PORTONE_TEST"))); prepare.execute(newer.getAttemptId());
        Map<String, List<Map<String, Object>>> before = snapshot(); when(clock.instant()).thenReturn(DELAY.minusNanos(1000));
        assertThat(list().data().path("totalElements").asInt()).isZero();
        when(clock.instant()).thenReturn(DELAY);
        Response page = http("GET", ROOT + "?size=1", null, admin());
        assertThat(page.status()).isEqualTo(200); assertThat(page.cacheControl()).isEqualTo("no-store");
        assertThat(page.data().path("totalElements").asInt()).isEqualTo(2);
        assertThat(page.data().path("content").get(0).path("attemptId").asText()).isEqualTo(first.getAttemptId());
        assertThat(http("GET", ROOT + "?size=1&page=1", null, admin()).data().path("content").get(0).path("attemptId").asText()).isEqualTo(second.getAttemptId());
        JsonNode detail = detail(first).data();
        assertThat(detail.path("firstPreparedAt").asText()).isEqualTo(BASE.toString());
        assertThat(detail.path("delayThresholdAt").asText()).isEqualTo(DELAY.toString());
        assertThat(detail.path("currentlyDelayed").asBoolean()).isTrue();
        assertThat(detail.path("automaticCancellationStoppedAt").isNull()).isTrue();
        assertThat(detail.path("nextLookupAt").asText()).isEqualTo(BASE.plusSeconds(5).toString());
        assertThat(http("GET", path(unprepared), null, admin()).status()).isEqualTo(404);
        assertThat(http("GET", path(simulated), null, admin()).status()).isEqualTo(404);
        assertThat(http("GET", path(newer), null, admin()).status()).isEqualTo(404);
        for (String hidden : List.of("cancellationRequestBody", "idempotencyKey", "claimId", "buyerSubject", "apiSecret", "receiptReason"))
            assertThat(detail.has(hidden)).as(hidden).isFalse();
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test void investigationRecordsJwtIdentityOriginalThresholdAndMultipleFindingsWithoutChangingWork() {
        RefundExecution execution = prepared(); when(clock.instant()).thenReturn(DELAY);
        Map<String, List<Map<String, Object>>> before = snapshot(); String command = id(); Response first = note(execution, command);
        assertThat(first.status()).isEqualTo(200); assertThat(first.cacheControl()).isEqualTo("no-store");
        assertThat(first.location()).isEqualTo(path(execution) + "/investigations");
        assertThat(first.data().path("operatorSubject").asText()).isEqualTo("usr_admin");
        assertThat(first.data().path("recordedAt").asText()).isEqualTo(DELAY.toString());
        assertThat(first.data().path("delayedSince").asText()).isEqualTo(DELAY.toString());
        when(clock.instant()).thenReturn(DELAY.plusSeconds(60));
        assertThat(note(execution, command).data()).isEqualTo(first.data());
        assertThat(note(execution, id()).status()).isEqualTo(200);
        Response history = http("GET", path(execution) + "/investigations?size=1", null, admin());
        assertThat(history.data().path("totalElements").asInt()).isEqualTo(2);
        assertThat(history.data().path("content").get(0)).isEqualTo(first.data());
        assertThat(history.cacheControl()).isEqualTo("no-store");
        Map<String, List<Map<String, Object>>> after = snapshot(); before.remove("refund_investigation"); after.remove("refund_investigation");
        assertThat(after).isEqualTo(before);
    }

    @Test void concurrentDuplicateCommandsConvergeAndChangedNotesOperatorsOrTargetsConflict() throws Exception {
        RefundExecution execution = prepared(); RefundExecution other = prepared(); when(clock.instant()).thenReturn(DELAY);
        String command = id(); CountDownLatch start = new CountDownLatch(1); ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Response> one = pool.submit(() -> { start.await(); return note(execution, command); });
            Future<Response> two = pool.submit(() -> { start.await(); return note(execution, command); });
            start.countDown(); Response first = one.get(15, TimeUnit.SECONDS); Response duplicate = two.get(15, TimeUnit.SECONDS);
            assertThat(first.status()).isEqualTo(200); assertThat(duplicate.status()).isEqualTo(200); assertThat(duplicate.data()).isEqualTo(first.data());
        } finally { start.countDown(); pool.shutdownNow(); }
        Map<String, List<Map<String, Object>>> before = snapshot();
        assertThat(http("POST", path(execution) + "/investigations", body(command, "다른 내용"), admin()).status()).isEqualTo(409);
        assertThat(http("POST", path(execution) + "/investigations", body(command, "PG 확인 내용"), token("other_admin", "ADMIN")).status()).isEqualTo(409);
        assertThat(note(other, command).status()).isEqualTo(409);
        assertThat(investigations.count()).isEqualTo(1); assertThat(snapshot()).isEqualTo(before);
    }

    @Test void concurrentCommandReuseAcrossExecutionsHasOneWinnerAndOneConflict() throws Exception {
        RefundExecution first = prepared(); RefundExecution second = prepared(); when(clock.instant()).thenReturn(DELAY);
        String command = id(); CountDownLatch inspected = new CountDownLatch(2);
        doAnswer(call -> {
            Optional<RefundInvestigation> result = entityManager.createQuery("select i from RefundInvestigation i where i.requestId=:requestId", RefundInvestigation.class)
                    .setParameter("requestId", command).getResultStream().findFirst();
            inspected.countDown(); await(inspected); return result;
        }).when(investigations).findByRequestId(command);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Response> one = pool.submit(() -> note(first, command)); Future<Response> two = pool.submit(() -> note(second, command));
            assertThat(List.of(one.get(15, TimeUnit.SECONDS).status(), two.get(15, TimeUnit.SECONDS).status())).containsExactlyInAnyOrder(200, 409);
            assertThat(investigations.count()).isEqualTo(1);
        } finally { reset(investigations); pool.shutdownNow(); }
    }

    @Test void confirmedMoneyRemovesTheTargetEvenIfAttemptStillPendingButKeepsHistoryAndOriginalReceipts() {
        RefundExecution execution = prepared(); when(clock.instant()).thenReturn(DELAY); String command = id(); Response receipt = note(execution, command);
        confirm(execution, DELAY.plusSeconds(1)); when(clock.instant()).thenReturn(DELAY.plusSeconds(2));
        Map<String, List<Map<String, Object>>> before = snapshot();
        assertThat(list().data().path("totalElements").asInt()).isZero();
        assertThat(stored(execution).getStatus()).isEqualTo(RefundExecutionStatus.PENDING);
        JsonNode detail = detail(execution).data();
        assertThat(detail.path("currentlyDelayed").asBoolean()).isFalse(); assertThat(detail.path("reconciliationRequired").asBoolean()).isTrue();
        assertThat(detail.path("refundConfirmationSource").asText()).isEqualTo("PG_QUERY_UNATTRIBUTED");
        assertThat(note(execution, id()).status()).isEqualTo(409); assertThat(note(execution, command).data()).isEqualTo(receipt.data());
        assertThat(http("GET", path(execution) + "/investigations", null, admin()).data().path("content").get(0)).isEqualTo(receipt.data());
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test void failureAndExplicitNextAttemptPreserveOldFindingsAndUseANewPreparationClock() {
        RefundExecution execution = prepared(); when(clock.instant()).thenReturn(DELAY); String command = id(); Response receipt = note(execution, command);
        Instant failedAt = DELAY.plusSeconds(1);
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            Payment payment = payments.lockByPaymentId(execution.getPaymentId()).orElseThrow();
            RefundExecution current = executions.lockByAttemptId(execution.getAttemptId()).orElseThrow();
            current.freezeCancellationBody("{\"amount\":10000,\"currentCancellableAmount\":10000,\"reason\":\"review\"}");
            PgRefundCancellationReceipt failed = new PgRefundCancellationReceipt(purchase(payment), current.getAttemptId(), "failed-cancellation", PgRefundCancellationReceipt.State.FAILED, BASE, 0, 0, "review");
            current.recordCancellationReceipt(payment, failed, failedAt);
            current.confirmFailure(payment, new PgRefundFailureConfirmation(failed, "a".repeat(64)), id(), failedAt);
        });
        when(clock.instant()).thenReturn(failedAt); assertThat(list().data().path("totalElements").asInt()).isZero();
        RefundExecutionRequest retry = new RefundExecutionRequest(id(), failedAt, execution.getRefundId(), id(), execution.getOrderNo(), execution.getPaymentId(),
                execution.getBuyerSubject(), execution.getAmount(), execution.getCurrency(), id(), execution.getAttemptId());
        RefundExecution next = receive.execute(retry); prepare.execute(next.getAttemptId());
        assertThat(list().data().path("totalElements").asInt()).isZero();
        assertThat(note(execution, command).data()).isEqualTo(receipt.data()); assertThat(note(execution, id()).status()).isEqualTo(409);
        when(clock.instant()).thenReturn(failedAt.plusSeconds(10800));
        assertThat(list().data().path("content").get(0).path("attemptId").asText()).isEqualTo(next.getAttemptId());
        assertThat(note(next, id()).data().path("delayedSince").asText()).isEqualTo(failedAt.plusSeconds(10800).toString());
        assertThat(http("GET", path(execution) + "/investigations", null, admin()).data().path("content").get(0)).isEqualTo(receipt.data());
    }

    @Test void rejectionHoldsRemainDelayTargetsAndFindingsCannotResumeOrExtendThem() {
        RefundExecution execution = prepared();
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            RefundExecution current = executions.lockByAttemptId(execution.getAttemptId()).orElseThrow();
            current.freezeCancellationBody("{\"reason\":\"review\"}");
            RefundCancellationClaim claim = current.getCancellationSchedule().claim(id(), BASE, Duration.ofSeconds(231), false);
            RefundCancellationHold hold = holds.saveAndFlush(RefundCancellationHold.invalidRequest(current.getAttemptId(), claim, BASE.plusSeconds(1), true));
            current.holdCancellation(hold.getHoldId(), claim);
            current.getLookupSchedule().claim(id(), DELAY.minusSeconds(1), Duration.ofSeconds(60));
        });
        when(clock.instant()).thenReturn(DELAY); Map<String, List<Map<String, Object>>> before = snapshot();
        JsonNode detail = detail(execution).data(); assertThat(detail.path("activeCancellationHoldReason").asText()).isEqualTo("PG_REQUEST_INVALID");
        assertThat(detail.path("lookupLeaseUntil").asText()).isEqualTo(DELAY.plusSeconds(59).toString());
        assertThat(detail.path("nextCancellationRetryAt").isNull()).isTrue(); assertThat(note(execution, id()).status()).isEqualTo(200);
        Map<String, List<Map<String, Object>>> after = snapshot(); before.remove("refund_investigation"); after.remove("refund_investigation"); assertThat(after).isEqualTo(before);
    }

    @Test void pendingSupplierReceiptsAndBalanceStopsRemainVisibleWhileOwnSuccessIsExcluded() {
        RefundExecution requested = prepared(); RefundExecution blocked = prepared(); RefundExecution succeeded = prepared();
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            Payment payment = payments.lockByPaymentId(requested.getPaymentId()).orElseThrow();
            RefundExecution current = executions.lockByAttemptId(requested.getAttemptId()).orElseThrow();
            current.freezeCancellationBody("{\"reason\":\"review\"}");
            current.recordCancellationReceipt(payment, new PgRefundCancellationReceipt(purchase(payment), current.getAttemptId(), "requested-id", PgRefundCancellationReceipt.State.REQUESTED, BASE, 0, 0, "review"), BASE.plusSeconds(1));
            executions.lockByAttemptId(blocked.getAttemptId()).orElseThrow().blockAutomaticCancellation(BASE.plusSeconds(2));
            Payment successfulPayment = payments.lockByPaymentId(succeeded.getPaymentId()).orElseThrow();
            RefundExecution successful = executions.lockByAttemptId(succeeded.getAttemptId()).orElseThrow();
            successful.freezeCancellationBody("{\"reason\":\"review\"}");
            successful.confirmCancellationSuccess(successfulPayment, new PgRefundCancellationSuccess(purchase(successfulPayment), successful.getAttemptId(),
                    new RefundCancellation("own-success", successful.getAmount(), BASE.plusSeconds(3)), BASE, 0, 0, "review"), id(), BASE.plusSeconds(3));
        });
        when(clock.instant()).thenReturn(DELAY); Map<String, List<Map<String, Object>>> before = snapshot();
        assertThat(list().data().path("totalElements").asInt()).isEqualTo(2);
        assertThat(detail(requested).data().path("receiptState").asText()).isEqualTo("REQUESTED");
        assertThat(detail(requested).data().path("receiptObservedAt").asText()).isEqualTo(BASE.plusSeconds(1).toString());
        assertThat(detail(blocked).data().path("cancellationBalanceChangedAt").asText()).isEqualTo(BASE.plusSeconds(2).toString());
        assertThat(detail(succeeded).data().path("currentlyDelayed").asBoolean()).isFalse();
        assertThat(note(succeeded, id()).status()).isEqualTo(409); assertThat(snapshot()).isEqualTo(before);
    }

    @Test void realFlushFailureRollsBackFindingsAndTheSameCommandCanRecover() {
        RefundExecution execution = prepared(); when(clock.instant()).thenReturn(DELAY); String command = id(); Map<String, List<Map<String, Object>>> before = snapshot();
        doAnswer(call -> {
            RefundInvestigation note = call.getArgument(0, RefundInvestigation.class); entityManager.persist(note); entityManager.flush();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refund_investigation WHERE request_id=?", Integer.class, command)).isEqualTo(1);
            throw new IllegalStateException("after actual investigation flush");
        }).when(investigations).saveAndFlush(any(RefundInvestigation.class));
        try { assertThat(note(execution, command).status()).isEqualTo(500); assertThat(snapshot()).isEqualTo(before); }
        finally { reset(investigations); }
        assertThat(note(execution, command).status()).isEqualTo(200); assertThat(investigations.count()).isEqualTo(1);
    }

    @Test void resultCommittedWhileRecordingWaitsForPaymentLockRejectsANewNote() throws Exception {
        RefundExecution execution = prepared(); when(clock.instant()).thenReturn(DELAY); CountDownLatch locking = new CountDownLatch(1);
        Long paymentRow = payments.findByPaymentId(execution.getPaymentId()).orElseThrow().getId(); ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Response> pending = new TransactionTemplate(transactions).execute(tx -> {
                Payment payment = payments.lockByPaymentId(execution.getPaymentId()).orElseThrow();
                doAnswer(call -> { locking.countDown(); return Optional.ofNullable(entityManager.find(Payment.class, paymentRow, LockModeType.PESSIMISTIC_WRITE)); })
                        .when(payments).lockByPaymentId(execution.getPaymentId());
                Future<Response> response = pool.submit(() -> note(execution, id())); await(locking);
                RefundExecution current = executions.lockByAttemptId(execution.getAttemptId()).orElseThrow();
                current.confirmRefund(payment, confirmation(payment, BASE.plusSeconds(60)), id(), DELAY); executions.flush(); return response;
            });
            assertThat(pending.get(15, TimeUnit.SECONDS).status()).isEqualTo(409); assertThat(investigations.count()).isZero();
        } finally { reset(payments); pool.shutdownNow(); }
    }

    @Test void invalidInputsAndAuthenticationCannotChangeFinancialOrInvestigationRecords() {
        RefundExecution execution = prepared(); when(clock.instant()).thenReturn(DELAY); Map<String, List<Map<String, Object>>> before = snapshot();
        for (String path : List.of(ROOT, path(execution), path(execution) + "/investigations")) {
            assertThat(http("GET", path, null, null).status()).isEqualTo(401);
            for (String role : List.of("MEMBER", "CREATOR", "REVIEWER")) assertThat(http("GET", path, null, token("usr_other", role)).status()).isEqualTo(403);
        }
        String notePath = path(execution) + "/investigations";
        assertThat(http("POST", notePath, body(id(), "확인"), null).status()).isEqualTo(401);
        for (String role : List.of("MEMBER", "CREATOR", "REVIEWER")) assertThat(http("POST", notePath, body(id(), "확인"), token("usr_other", role)).status()).isEqualTo(403);
        for (String bearer : List.of(token("usr_admin", "ADMIN", "https://wrong", "esd-api", Instant.now().plusSeconds(60)),
                token("usr_admin", "ADMIN", ISSUER, "wrong", Instant.now().plusSeconds(60)), token("usr_admin", "ADMIN", ISSUER, "esd-api", Instant.now().minusSeconds(120))))
            assertThat(http("POST", notePath, body(id(), "확인"), bearer).status()).isEqualTo(401);
        for (Object malformed : List.of(Map.of(), body("bad", "확인"), body(id(), "\t\n"), body(id(), "x".repeat(501))))
            assertThat(http("POST", notePath, malformed, admin()).status()).isEqualTo(400);
        for (String suffix : List.of("?page=-1", "?size=0", "?size=101", "?page=wrong"))
            assertThat(http("GET", ROOT + suffix, null, admin()).status()).isEqualTo(400);
        assertThat(http("GET", ROOT + "/bad", null, admin()).status()).isEqualTo(400);
        assertThat(http("GET", ROOT + "/" + id(), null, admin()).status()).isEqualTo(404);
        assertThat(http("POST", ROOT + "/" + id() + "/investigations", body(id(), "확인"), admin()).status()).isEqualTo(404);
        for (String method : List.of("PUT", "PATCH", "DELETE")) assertThat(http(method, notePath, body(id(), "확인"), admin()).status()).isEqualTo(403);
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test void earlyOrUnpreparedAttemptsCannotReceiveFindingsAndClientTimesCannotBackdateThem() {
        RefundExecution execution = prepared(); when(clock.instant()).thenReturn(DELAY.minusNanos(1000));
        assertThat(note(execution, id()).status()).isEqualTo(409);
        when(clock.instant()).thenReturn(BASE); RefundExecution unknown = receive.execute(execution(paid("PORTONE_TEST")));
        when(clock.instant()).thenReturn(DELAY.plusSeconds(100)); assertThat(note(unknown, id()).status()).isEqualTo(409);
        assertThat(note(execution, id()).data().path("recordedAt").asText()).isEqualTo(DELAY.plusSeconds(100).toString());
    }

    @Tag("migration")
    @Test void v27PreservesExistingDataAddsNoInventedNotesAndEnforcesAuditConstraints() {
        RefundExecution execution = prepared(); MySQLContainer<?> mysql = SharedContainers.MYSQL;
        String schema = "investigation_upgrade_" + id().replace("-", ""); String server = "jdbc:mysql://" + mysql.getHost() + ":" + mysql.getMappedPort(3306) + "/";
        String options = "?useSSL=false&allowPublicKeyRetrieval=true";
        JdbcTemplate admin = new JdbcTemplate(new DriverManagerDataSource(server + mysql.getDatabaseName() + options, "root", mysql.getPassword()));
        admin.execute("CREATE DATABASE " + schema);
        try {
            DriverManagerDataSource source = new DriverManagerDataSource(server + schema + options, "root", mysql.getPassword()); JdbcTemplate legacy = new JdbcTemplate(source);
            String location = "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath();
            Flyway.configure().dataSource(source).locations(location).target("26").load().migrate();
            for (String table : List.of("purchase_payment", "refund_execution", "processed_event", "outbox_event")) {
                List<String> columns = legacy.queryForList("SELECT column_name FROM information_schema.columns WHERE table_schema=? AND table_name=? AND generation_expression='' ORDER BY ordinal_position", String.class, schema, table);
                String copied = String.join(",", columns);
                legacy.update("INSERT INTO " + table + "(" + copied + ") SELECT " + copied + " FROM " + mysql.getDatabaseName() + "." + table);
            }
            Map<String, List<Map<String, Object>>> before = new LinkedHashMap<>();
            for (String table : List.of("purchase_payment", "refund_execution", "processed_event", "outbox_event")) before.put(table, legacy.queryForList("SELECT * FROM " + table + " ORDER BY id"));
            Flyway flyway = Flyway.configure().dataSource(source).locations(location).target("27").load(); flyway.migrate(); flyway.validate();
            for (String table : before.keySet()) assertThat(legacy.queryForList("SELECT * FROM " + table + " ORDER BY id")).isEqualTo(before.get(table));
            assertThat(legacy.queryForObject("SELECT COUNT(*) FROM refund_investigation", Integer.class)).isZero();
            String insert = "INSERT INTO refund_investigation(request_id,attempt_id,operator_subject,note,recorded_at,delayed_since) VALUES(?,?,?,?,'2026-10-29 04:00:00','2026-10-29 04:00:00')";
            String command = id(); legacy.update(insert, command, execution.getAttemptId(), "usr_admin", "확인");
            assertThatThrownBy(() -> legacy.update(insert, command, execution.getAttemptId(), "usr_admin", "다른 내용")).isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> legacy.update(insert, id(), id(), "usr_admin", "확인")).isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> legacy.update(insert, id(), execution.getAttemptId(), "usr_admin", " ")).isInstanceOf(DataAccessException.class).hasMessageContaining("chk_refund_investigation_note");
            assertThatThrownBy(() -> legacy.update("UPDATE refund_investigation SET recorded_at='2026-10-29 03:59:59' WHERE request_id=?", command)).isInstanceOf(DataAccessException.class).hasMessageContaining("chk_refund_investigation_time");
            flyway.migrate(); assertThat(legacy.queryForObject("SELECT COUNT(*) FROM refund_investigation", Integer.class)).isEqualTo(1);
        } finally { admin.execute("DROP DATABASE " + schema); }
    }

    private Payment paid(String provider) {
        Payment payment = Payment.receive(request(), provider);
        payment.confirm(new PgResult(provider, payment.getPaymentId(), payment.getOrderNo(), payment.getAmount(), "KRW", "pg-" + id(), BASE.minusSeconds(60), PgResult.Decision.APPROVED, null, null, null), id(), BASE.minusSeconds(30));
        return payments.saveAndFlush(payment);
    }
    private RefundExecution prepared() {
        when(clock.instant()).thenReturn(BASE); RefundExecution execution = receive.execute(execution(paid("PORTONE_TEST"))); prepare.execute(execution.getAttemptId()); return stored(execution);
    }
    private RefundExecution stored(RefundExecution execution) { return executions.findByAttemptId(execution.getAttemptId()).orElseThrow(); }
    private PgRefundLookupRequest purchase(Payment payment) {
        return new PgRefundLookupRequest(payment.getPaymentId(), payment.getOrderNo(), payment.getAmount(), payment.getCurrency(), payment.getProviderTransactionId(), payment.getProviderOccurredAt());
    }
    private PgRefundConfirmation confirmation(Payment payment, Instant at) { return new PgRefundConfirmation(purchase(payment), List.of(new RefundCancellation("cancel-" + id(), payment.getAmount(), at))); }
    private void confirm(RefundExecution execution, Instant at) {
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            Payment payment = payments.lockByPaymentId(execution.getPaymentId()).orElseThrow();
            executions.lockByAttemptId(execution.getAttemptId()).orElseThrow().confirmRefund(payment, confirmation(payment, BASE.plusSeconds(60)), id(), at);
        });
    }
    private Map<String, List<Map<String, Object>>> snapshot() {
        Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
        for (String table : List.of("refund_investigation", "refund_cancellation_hold", "refund_execution", "purchase_payment", "processed_event", "outbox_event"))
            result.put(table, jdbc.queryForList("SELECT * FROM " + table + " ORDER BY id"));
        return result;
    }
    private static void await(CountDownLatch latch) {
        try { assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue(); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
    }
    private String admin() { return token("usr_admin", "ADMIN"); }
    private String path(RefundExecution execution) { return ROOT + "/" + execution.getAttemptId(); }
    private Map<String, String> body(String command, String note) { return Map.of("requestId", command, "note", note, "operatorSubject", "forged", "recordedAt", "2000-01-01T00:00:00Z"); }
    private Response list() { return http("GET", ROOT, null, admin()); }
    private Response detail(RefundExecution execution) { return http("GET", path(execution), null, admin()); }
    private Response note(RefundExecution execution, String command) { return http("POST", path(execution) + "/investigations", body(command, "PG 확인 내용"), admin()); }
    private Response http(String method, String path, Object body, String bearer) {
        RestClient.RequestBodySpec request = RestClient.create("http://127.0.0.1:" + port).method(HttpMethod.valueOf(method)).uri(path);
        if (bearer != null) request.header("Authorization", "Bearer " + bearer);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).body(body);
        return request.exchange((sent, reply) -> new Response(reply.getStatusCode().value(), reply.getHeaders().getFirst("Location"), reply.getHeaders().getFirst("Cache-Control"), mapper.readTree(reply.getBody())));
    }
    private record Response(int status, String location, String cacheControl, JsonNode body) { JsonNode data() { return body.path("data"); } }
}
