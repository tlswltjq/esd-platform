package com.stove.payment;

import static com.stove.payment.PaymentFixtures.id;
import static com.stove.payment.PaymentFixtures.request;
import static com.stove.payment.PortOneFixtures.CHANNEL;
import static com.stove.payment.PortOneFixtures.SECRET;
import static com.stove.payment.PortOneFixtures.STORE;
import static com.stove.payment.RefundFixtures.execution;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.kafka.contract.EventType;
import com.stove.common.testcontainers.SharedContainers;
import com.stove.payment.core.domain.Payment;
import com.stove.payment.core.domain.PaymentRepository;
import com.stove.payment.core.domain.PgPaymentNotFound;
import com.stove.payment.core.domain.PgRefundCancellationReceipt;
import com.stove.payment.core.domain.PgRefundCancellationSuccess;
import com.stove.payment.core.domain.PgRefundConfirmation;
import com.stove.payment.core.domain.PgRefundFailureConfirmation;
import com.stove.payment.core.domain.PgRefundLookupRequest;
import com.stove.payment.core.domain.PgResult;
import com.stove.payment.core.domain.PgUnavailable;
import com.stove.payment.core.domain.RefundCancellation;
import com.stove.payment.core.domain.RefundClosureRepository;
import com.stove.payment.core.domain.RefundExecution;
import com.stove.payment.core.domain.RefundExecutionRepository;
import com.stove.payment.core.domain.RefundExecutionStatus;
import com.stove.payment.core.domain.RefundLookupResult;
import com.stove.payment.core.port.RefundPaymentLookup;
import com.stove.payment.core.service.ReadRefundDelays;
import com.stove.payment.core.service.ReceiveRefundExecution;
import com.stove.payment.core.service.RecordRefundCancellationPreparation;
import io.micrometer.core.instrument.MeterRegistry;
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
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.MySQLContainer;

/**
 * Decision 127: an operator closes a delayed or unattributed refund execution only on a full cancellation of the same
 * original payment re-inspected at closure time. Real HTTP, JWT and MySQL; the PortOne lookup is a contract fixture.
 */
@TestPropertySource(properties = "stove.payment.pg.provider=PORTONE_TEST")
class RefundDelayClosureIntegrationTest extends PaymentDatabaseIntegrationTestBase {
    private static final String ROOT = "/api/v1/admin/refund-delays";
    private static final Instant BASE = Payment.now();
    private static final Instant DELAY = BASE.plusSeconds(10800);

    @DynamicPropertySource static void portone(DynamicPropertyRegistry registry) {
        registry.add("stove.payment.portone.base-url", () -> "http://127.0.0.1:9");
        registry.add("stove.payment.portone.api-secret", () -> "fixture-api-secret");
        registry.add("stove.payment.portone.store-id", () -> STORE); registry.add("stove.payment.portone.channel-key", () -> CHANNEL);
        registry.add("stove.payment.portone.webhook-secret", () -> SECRET);
        registry.add("stove.payment.portone.webhook-url", () -> "https://payment-test.example/api/v1/payments/webhooks/portone");
        registry.add("stove.payment.approval.order-base-url", () -> "http://127.0.0.1:9");
        registry.add("stove.payment.approval.order-service-key", () -> "fixture-order-service-key");
        registry.add("stove.payment.approval.payload-key", () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
    }

    @Autowired PaymentRepository payments;
    @Autowired RefundExecutionRepository executions;
    @Autowired RefundClosureRepository closures;
    @Autowired ReadRefundDelays delays;
    @Autowired ReceiveRefundExecution receive;
    @Autowired RecordRefundCancellationPreparation prepare;
    @Autowired PlatformTransactionManager transactions;
    @Autowired MeterRegistry meters;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @MockitoBean RefundPaymentLookup lookup;
    @MockitoBean Clock clock;
    @LocalServerPort int port;

    @BeforeEach @AfterEach void clear() {
        reset(lookup); when(clock.instant()).thenReturn(BASE);
        // The closure audit (V39) is not cascaded from its execution, so it is removed before the executions it refers to.
        jdbc.update("DELETE FROM refund_closure");
        jdbc.update("DELETE FROM refund_investigation"); jdbc.update("DELETE FROM payment_pg_webhook"); jdbc.update("DELETE FROM refund_execution ORDER BY id DESC");
        jdbc.update("DELETE FROM payment_order_lifecycle"); jdbc.update("DELETE FROM purchase_payment"); jdbc.update("DELETE FROM processed_event"); jdbc.update("DELETE FROM outbox_event");
    }

    @Test void aDelayedExecutionIsCompletedThroughTheLookupPathAndTheJudgmentIsRecorded() {
        RefundExecution execution = prepared(); Payment payment = payment(execution);
        PgRefundConfirmation console = confirmation(payment, "console-1");
        when(lookup.find(PgRefundLookupRequest.from(payment))).thenAnswer(call -> { when(clock.instant()).thenReturn(DELAY.plusSeconds(2)); return Optional.of(console); });
        when(clock.instant()).thenReturn(DELAY.plusSeconds(1));
        assertThat(delays.count()).isEqualTo(1); assertThat(delays.unattributedOpen()).isZero();
        String command = id();
        Reply closed = close(execution, command, "EXTERNAL_CANCELLATION", "15:00 콘솔에서 9,000원 취소");
        assertThat(closed.status()).isEqualTo(200); assertThat(closed.cacheControl()).isEqualTo("no-store");
        assertThat(closed.location()).isEqualTo(path(execution));
        JsonNode data = closed.data();
        assertThat(data.path("executionStatus").asText()).isEqualTo("PENDING");
        assertThat(data.path("refundConfirmationSource").asText()).isEqualTo("PG_QUERY_UNATTRIBUTED");
        assertThat(data.path("refundConfirmedAt").asText()).isEqualTo(DELAY.plusSeconds(2).toString());
        assertThat(data.path("closure").path("outcome").asText()).isEqualTo("COMPLETED");
        assertThat(data.path("closure").path("attribution").asText()).isEqualTo("EXTERNAL_CANCELLATION");
        assertThat(data.path("closure").path("operatorSubject").asText()).isEqualTo("usr_admin");
        assertThat(data.path("closure").path("evidenceObservedAt").asText()).isEqualTo(DELAY.plusSeconds(2).toString());
        assertThat(data.path("closure").path("evidenceRefundedAt").asText()).isEqualTo(console.cancelledAt().toString());
        RefundExecution stored = stored(execution);
        assertThat(stored.getRefundConfirmedAt()).isEqualTo(DELAY.plusSeconds(2));
        assertThat(stored.getConfirmedCancellations()).extracting(RefundCancellation::getCancellationId).containsExactly("console-1");
        assertThat(stored.getLookupSchedule().getNextAt()).isNull(); assertThat(stored.getLookupSchedule().getLeaseUntil()).isNull();
        // The existing completion notice, exactly once, so Order completes the customer's refund as before.
        assertThat(events(EventType.REFUND_PAYMENT_CONFIRMED)).isEqualTo(1);
        assertThat(delays.count()).isZero(); assertThat(delays.unattributedOpen()).isZero();
        assertThat(gauge()).isZero();
        JsonNode detail = http("GET", path(execution), null, admin()).data();
        assertThat(detail.path("reconciliationRequired").asBoolean()).isFalse();
        assertThat(detail.path("closure").path("requestId").asText()).isEqualTo(command);
        verify(lookup, times(1)).find(any()); verify(lookup, never()).inspect(any(), any());
    }

    @Test void anUnattributedExecutionIsListedCountedAndClosedByRecordingTheJudgmentOnly() {
        RefundExecution execution = prepared(); Payment payment = payment(execution);
        PgRefundConfirmation found = confirmation(payment, "found-1");
        Instant confirmed = BASE.plusSeconds(60); confirm(execution, found, confirmed);
        when(clock.instant()).thenReturn(confirmed.plusSeconds(1));
        // Inside the original window: not delayed, but an investigation target (decision 127).
        assertThat(delays.count()).isZero(); assertThat(delays.unattributedOpen()).isEqualTo(1); assertThat(gauge()).isEqualTo(1);
        assertThat(http("GET", ROOT, null, admin()).data().path("totalElements").asInt()).isZero();
        Reply listed = http("GET", ROOT + "?kind=UNATTRIBUTED", null, admin());
        assertThat(listed.status()).isEqualTo(200); assertThat(listed.cacheControl()).isEqualTo("no-store");
        assertThat(listed.data().path("totalElements").asInt()).isEqualTo(1);
        assertThat(listed.data().path("content").get(0).path("attemptId").asText()).isEqualTo(execution.getAttemptId());
        assertThat(listed.data().path("content").get(0).path("reconciliationRequired").asBoolean()).isTrue();
        assertThat(http("GET", path(execution), null, admin()).data().path("closure").isNull()).isTrue();
        long notices = events(EventType.REFUND_PAYMENT_CONFIRMED);
        when(lookup.find(PgRefundLookupRequest.from(payment))).thenReturn(Optional.of(found));
        Reply closed = close(execution, id(), "OUR_REQUEST", "응답 유실 뒤 우리 취소로 판단");
        assertThat(closed.status()).isEqualTo(200);
        assertThat(closed.data().path("closure").path("outcome").asText()).isEqualTo("RECORDED");
        assertThat(closed.data().path("refundConfirmedAt").asText()).isEqualTo(confirmed.toString());
        RefundExecution stored = stored(execution);
        assertThat(stored.getStatus()).isEqualTo(RefundExecutionStatus.PENDING);
        assertThat(stored.getRefundConfirmedAt()).isEqualTo(confirmed); assertThat(stored.getRefundConfirmationSource()).isEqualTo("PG_QUERY_UNATTRIBUTED");
        assertThat(stored.getLookupSchedule().getLeaseUntil()).isNull(); assertThat(stored.getLookupSchedule().getClaimId()).isNull();
        assertThat(events(EventType.REFUND_PAYMENT_CONFIRMED)).as("no new money notice").isEqualTo(notices);
        assertThat(delays.unattributedOpen()).isZero(); assertThat(gauge()).isZero();
        assertThat(http("GET", ROOT + "?kind=UNATTRIBUTED", null, admin()).data().path("totalElements").asInt()).isZero();
        JsonNode detail = http("GET", path(execution), null, admin()).data();
        assertThat(detail.path("reconciliationRequired").asBoolean()).isFalse();
        assertThat(detail.path("closure").path("attribution").asText()).isEqualTo("OUR_REQUEST");
    }

    @Test void theSameCommandReturnsTheFirstReceiptAndChangedOrRepeatedCommandsConflict() {
        RefundExecution execution = prepared(); RefundExecution other = prepared(); Payment payment = payment(execution);
        when(lookup.find(PgRefundLookupRequest.from(payment))).thenReturn(Optional.of(confirmation(payment, "console-1")));
        when(clock.instant()).thenReturn(DELAY);
        String command = id(); Reply first = close(execution, command, "UNDETERMINED", "판단 불가");
        assertThat(first.status()).isEqualTo(200);
        when(clock.instant()).thenReturn(DELAY.plusSeconds(60));
        assertThat(close(execution, command, "UNDETERMINED", "판단 불가").data()).isEqualTo(first.data());
        Map<String, List<Map<String, Object>>> before = snapshot();
        assertThat(code(close(execution, command, "OUR_REQUEST", "판단 불가"))).isEqualTo("REFUND_CLOSURE_CHANGED");
        assertThat(code(close(execution, command, "UNDETERMINED", "다른 근거"))).isEqualTo("REFUND_CLOSURE_CHANGED");
        assertThat(code(http("POST", path(execution) + "/closure", body(command, "UNDETERMINED", "판단 불가"), token("other_admin", "ADMIN")))).isEqualTo("REFUND_CLOSURE_CHANGED");
        assertThat(code(close(other, command, "UNDETERMINED", "판단 불가"))).isEqualTo("REFUND_CLOSURE_CHANGED");
        assertThat(code(close(execution, id(), "UNDETERMINED", "판단 불가"))).isEqualTo("REFUND_NOT_CLOSABLE");
        assertThat(snapshot()).isEqualTo(before);
        verify(lookup, times(1)).find(any());
    }

    @Test void aPaidPurchaseIsRefusedTheLeaseReturnsAndTheWorkerScheduleIsKept() {
        RefundExecution execution = prepared(); Payment payment = payment(execution);
        when(lookup.find(PgRefundLookupRequest.from(payment))).thenReturn(Optional.empty());
        when(clock.instant()).thenReturn(DELAY); Map<String, List<Map<String, Object>>> before = snapshot();
        Reply refused = close(execution, id(), "EXTERNAL_CANCELLATION", "콘솔 취소 전");
        assertThat(refused.status()).isEqualTo(409); assertThat(code(refused)).isEqualTo("REFUND_CLOSURE_PG_NOT_CANCELLED");
        assertThat(snapshot()).isEqualTo(before);
        assertThat(stored(execution).getLookupSchedule().getNextAt()).isEqualTo(BASE.plusSeconds(5));
        assertThat(delays.count()).isEqualTo(1);
    }

    @Test void aLinkedFailureOrPendingCancellationIsNotAFullCancellationAndIsNotRecordedByTheClosure() {
        RefundExecution execution = prepared(); Payment payment = payment(execution);
        PgRefundCancellationReceipt failed = new PgRefundCancellationReceipt(PgRefundLookupRequest.from(payment), execution.getAttemptId(),
                "failed-cancellation", PgRefundCancellationReceipt.State.FAILED, BASE, 0, 0, "review");
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            Payment current = payments.lockByPaymentId(payment.getPaymentId()).orElseThrow();
            RefundExecution locked = executions.lockByAttemptId(execution.getAttemptId()).orElseThrow();
            locked.freezeCancellationBody("{\"reason\":\"review\"}");
            locked.recordCancellationReceipt(current, failed, BASE.plusSeconds(1));
        });
        when(lookup.inspect(any(), any())).thenReturn(new RefundLookupResult(null, new PgRefundFailureConfirmation(failed, "a".repeat(64))));
        when(clock.instant()).thenReturn(DELAY); Map<String, List<Map<String, Object>>> before = snapshot();
        assertThat(code(close(execution, id(), "UNDETERMINED", "실패 응답 확인"))).isEqualTo("REFUND_CLOSURE_PG_NOT_CANCELLED");
        verify(lookup).inspect(PgRefundLookupRequest.from(payment), failed); verify(lookup, never()).find(any());
        assertThat(snapshot()).isEqualTo(before);
        assertThat(stored(execution).getStatus()).isEqualTo(RefundExecutionStatus.PENDING);
    }

    @Test void pgOutagesAndInvalidEvidenceReturnTheLeaseWithoutChangingAnything() {
        RefundExecution execution = prepared(); when(clock.instant()).thenReturn(DELAY);
        Map<String, List<Map<String, Object>>> before = snapshot();
        for (RuntimeException failure : List.of(new PgUnavailable("down"), new PgPaymentNotFound(), new IllegalArgumentException("mismatch"))) {
            reset(lookup); when(lookup.find(any())).thenThrow(failure);
            String expected = failure instanceof IllegalArgumentException ? "CLOSURE_PG_INVALID_EVIDENCE" : "CLOSURE_PG_UNAVAILABLE";
            assertThat(code(close(execution, id(), "UNDETERMINED", "조회"))).as(failure.getClass().getSimpleName()).isEqualTo(expected);
            assertThat(snapshot()).isEqualTo(before);
        }
    }

    @Test void onlyDelayedOrUnattributedPendingExecutionsCanBeClosedAndThePgIsNotCalledOtherwise() {
        RefundExecution early = prepared();
        when(clock.instant()).thenReturn(BASE); RefundExecution unprepared = receive.execute(execution(paid()));
        RefundExecution succeeded = prepared(); RefundExecution failed = prepared();
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            Payment payment = payments.lockByPaymentId(succeeded.getPaymentId()).orElseThrow();
            RefundExecution current = executions.lockByAttemptId(succeeded.getAttemptId()).orElseThrow();
            current.freezeCancellationBody("{\"reason\":\"review\"}");
            current.confirmCancellationSuccess(payment, new PgRefundCancellationSuccess(PgRefundLookupRequest.from(payment), current.getAttemptId(),
                    new RefundCancellation("own", current.getAmount(), BASE.plusSeconds(3)), BASE, 0, 0, "review"), id(), BASE.plusSeconds(3));
            Payment failedPayment = payments.lockByPaymentId(failed.getPaymentId()).orElseThrow();
            RefundExecution failing = executions.lockByAttemptId(failed.getAttemptId()).orElseThrow();
            failing.freezeCancellationBody("{\"reason\":\"review\"}");
            PgRefundCancellationReceipt receipt = new PgRefundCancellationReceipt(PgRefundLookupRequest.from(failedPayment), failing.getAttemptId(),
                    "failed", PgRefundCancellationReceipt.State.FAILED, BASE, 0, 0, "review");
            failing.recordCancellationReceipt(failedPayment, receipt, BASE.plusSeconds(1));
            failing.confirmFailure(failedPayment, new PgRefundFailureConfirmation(receipt, "a".repeat(64)), id(), BASE.plusSeconds(1));
        });
        when(clock.instant()).thenReturn(DELAY.minusNanos(1000));
        assertThat(code(close(early, id(), "UNDETERMINED", "이른 종결"))).isEqualTo("REFUND_NOT_CLOSABLE");
        when(clock.instant()).thenReturn(DELAY.plusSeconds(1)); Map<String, List<Map<String, Object>>> before = snapshot();
        for (RefundExecution target : List.of(unprepared, succeeded, failed))
            assertThat(code(close(target, id(), "UNDETERMINED", "대상 아님"))).isEqualTo("REFUND_NOT_CLOSABLE");
        assertThat(http("POST", ROOT + "/" + id() + "/closure", body(id(), "UNDETERMINED", "없음"), admin()).status()).isEqualTo(404);
        assertThat(snapshot()).isEqualTo(before);
        verify(lookup, never()).find(any()); verify(lookup, never()).inspect(any(), any());
    }

    @Test void aLiveWorkerLeaseRefusesTheClosureAndALeaseLostDuringTheLookupSavesNothing() {
        RefundExecution execution = prepared(); Payment payment = payment(execution);
        new TransactionTemplate(transactions).executeWithoutResult(tx ->
                executions.lockByAttemptId(execution.getAttemptId()).orElseThrow().getLookupSchedule().claim(id(), DELAY.minusSeconds(1), Duration.ofSeconds(60)));
        when(clock.instant()).thenReturn(DELAY);
        assertThat(code(close(execution, id(), "UNDETERMINED", "작업자 조회 중"))).isEqualTo("REFUND_CLOSURE_BUSY");
        verify(lookup, never()).find(any());
        when(clock.instant()).thenReturn(DELAY.plusSeconds(60));
        String thief = id();
        when(lookup.find(PgRefundLookupRequest.from(payment))).thenAnswer(call -> {
            jdbc.update("UPDATE refund_execution SET refund_lookup_claim_id=? WHERE attempt_id=?", thief, execution.getAttemptId());
            return Optional.of(confirmation(payment, "console-1"));
        });
        assertThat(code(close(execution, id(), "UNDETERMINED", "lease 상실"))).isEqualTo("REFUND_CLOSURE_BUSY");
        RefundExecution stored = stored(execution);
        assertThat(stored.getRefundConfirmedAt()).isNull(); assertThat(stored.getLookupSchedule().getClaimId()).isEqualTo(thief);
        assertThat(closures.count()).isZero(); assertThat(events(EventType.REFUND_PAYMENT_CONFIRMED)).isZero();
    }

    @Test void differentPgEvidenceForAnUnattributedExecutionConflictsAndIsNotRecorded() {
        RefundExecution execution = prepared(); Payment payment = payment(execution);
        confirm(execution, confirmation(payment, "found-1"), BASE.plusSeconds(60));
        when(lookup.find(PgRefundLookupRequest.from(payment))).thenReturn(Optional.of(confirmation(payment, "another-1")));
        when(clock.instant()).thenReturn(BASE.plusSeconds(61)); Map<String, List<Map<String, Object>>> before = snapshot();
        Reply conflict = close(execution, id(), "OUR_REQUEST", "근거 다름");
        assertThat(conflict.status()).isEqualTo(409); assertThat(code(conflict)).isEqualTo("CONFLICT");
        assertThat(snapshot()).isEqualTo(before); assertThat(delays.unattributedOpen()).isEqualTo(1);
    }

    @Test void concurrentClosuresOfOneExecutionHaveOneWinner() throws Exception {
        RefundExecution execution = prepared(); Payment payment = payment(execution);
        CountDownLatch inside = new CountDownLatch(1); CountDownLatch release = new CountDownLatch(1);
        when(lookup.find(PgRefundLookupRequest.from(payment))).thenAnswer(call -> {
            inside.countDown(); assertThat(release.await(10, TimeUnit.SECONDS)).isTrue(); return Optional.of(confirmation(payment, "console-1"));
        });
        when(clock.instant()).thenReturn(DELAY); ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Reply> first = pool.submit(() -> close(execution, id(), "EXTERNAL_CANCELLATION", "첫 운영자"));
            assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(code(close(execution, id(), "OUR_REQUEST", "둘째 운영자"))).isEqualTo("REFUND_CLOSURE_BUSY");
            release.countDown();
            assertThat(first.get(15, TimeUnit.SECONDS).status()).isEqualTo(200);
        } finally { release.countDown(); pool.shutdownNow(); }
        assertThat(closures.count()).isEqualTo(1); assertThat(events(EventType.REFUND_PAYMENT_CONFIRMED)).isEqualTo(1);
        verify(lookup, times(1)).find(any());
    }

    /** An unattributed execution has no worker lease; the row lock and the attempt UNIQUE let only one closure commit. */
    @Test void concurrentClosuresOfAnUnattributedExecutionHaveOneWinnerWithoutALease() throws Exception {
        RefundExecution execution = prepared(); Payment payment = payment(execution);
        PgRefundConfirmation found = confirmation(payment, "found-1"); confirm(execution, found, BASE.plusSeconds(60));
        CountDownLatch inside = new CountDownLatch(2); String first = id(); String second = id();
        when(lookup.find(PgRefundLookupRequest.from(payment))).thenAnswer(call -> {
            inside.countDown(); assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue(); return Optional.of(found);
        });
        when(clock.instant()).thenReturn(BASE.plusSeconds(61)); ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Reply> one = pool.submit(() -> close(execution, first, "OUR_REQUEST", "첫 운영자"));
            Future<Reply> two = pool.submit(() -> close(execution, second, "UNDETERMINED", "둘째 운영자"));
            Reply a = one.get(15, TimeUnit.SECONDS); Reply b = two.get(15, TimeUnit.SECONDS);
            assertThat(List.of(a.status(), b.status())).containsExactlyInAnyOrder(200, 409);
            assertThat(code(a.status() == 409 ? a : b)).isEqualTo("REFUND_NOT_CLOSABLE");
        } finally { pool.shutdownNow(); }
        assertThat(closures.count()).isEqualTo(1);
        assertThat(events(EventType.REFUND_PAYMENT_CONFIRMED)).as("recording a judgment publishes no money notice").isZero();
        assertThat(stored(execution).getLookupSchedule().getLeaseUntil()).isNull();
    }

    @Test void onlyAnAdministratorWithAValidCommandCanClose() {
        RefundExecution execution = prepared(); when(clock.instant()).thenReturn(DELAY); Map<String, List<Map<String, Object>>> before = snapshot();
        String closure = path(execution) + "/closure";
        assertThat(http("POST", closure, body(id(), "UNDETERMINED", "확인"), null).status()).isEqualTo(401);
        for (String role : List.of("MEMBER", "CREATOR", "REVIEWER"))
            assertThat(http("POST", closure, body(id(), "UNDETERMINED", "확인"), token("usr_other", role)).status()).isEqualTo(403);
        for (Object malformed : List.of(Map.of(), body("bad", "UNDETERMINED", "확인"), body(id(), "CONSOLE", "확인"), body(id(), "UNDETERMINED", " "),
                body(id(), "UNDETERMINED", "x".repeat(501))))
            assertThat(http("POST", closure, malformed, admin()).status()).isEqualTo(400);
        assertThat(http("GET", ROOT + "?kind=OTHER", null, admin()).status()).isEqualTo(400);
        for (String method : List.of("PUT", "PATCH", "DELETE")) assertThat(http(method, closure, body(id(), "UNDETERMINED", "확인"), admin()).status()).isEqualTo(403);
        assertThat(snapshot()).isEqualTo(before);
        verify(lookup, never()).find(any());
    }

    /**
     * Decision 104: V39 only adds a table. Rows written by V38 — including an unattributed execution — are unchanged and have
     * no closure, so they are open targets; the closure audit keeps its constraints and cannot be erased with its execution.
     */
    @Tag("migration")
    @Test void v39PreservesExistingExecutionsAddsNoClosuresAndEnforcesAuditConstraints() {
        RefundExecution open = prepared(); confirm(open, confirmation(payment(open), "found-1"), BASE.plusSeconds(60));
        MySQLContainer<?> mysql = SharedContainers.MYSQL;
        String schema = "refund_closure_upgrade_" + id().replace("-", ""); String server = "jdbc:mysql://" + mysql.getHost() + ":" + mysql.getMappedPort(3306) + "/";
        String options = "?useSSL=false&allowPublicKeyRetrieval=true";
        JdbcTemplate admin = new JdbcTemplate(new DriverManagerDataSource(server + mysql.getDatabaseName() + options, "root", mysql.getPassword()));
        admin.execute("CREATE DATABASE " + schema);
        try {
            DriverManagerDataSource source = new DriverManagerDataSource(server + schema + options, "root", mysql.getPassword()); JdbcTemplate legacy = new JdbcTemplate(source);
            String location = "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath();
            Flyway.configure().dataSource(source).locations(location).target("38").load().migrate();
            List<String> tables = List.of("purchase_payment", "refund_execution", "refund_cancellation_evidence", "outbox_event");
            for (String table : tables) {
                List<String> columns = legacy.queryForList("SELECT column_name FROM information_schema.columns WHERE table_schema=? AND table_name=? AND generation_expression='' ORDER BY ordinal_position", String.class, schema, table);
                String copied = String.join(",", columns);
                legacy.update("INSERT INTO " + table + "(" + copied + ") SELECT " + copied + " FROM " + mysql.getDatabaseName() + "." + table);
            }
            Map<String, List<Map<String, Object>>> before = new LinkedHashMap<>();
            for (String table : tables) before.put(table, legacy.queryForList("SELECT * FROM " + table));
            Flyway flyway = Flyway.configure().dataSource(source).locations(location).target("39").load(); flyway.migrate(); flyway.validate();
            for (String table : tables) assertThat(legacy.queryForList("SELECT * FROM " + table)).as(table).isEqualTo(before.get(table));
            assertThat(legacy.queryForObject("SELECT COUNT(*) FROM refund_closure", Integer.class)).isZero();
            // The same filter as the gauge and the UNATTRIBUTED list: the old unattributed row is an open target.
            assertThat(legacy.queryForObject("SELECT COUNT(*) FROM refund_execution r WHERE r.status='PENDING' AND r.refund_confirmed_at IS NOT NULL "
                    + "AND r.refund_confirmation_source='PG_QUERY_UNATTRIBUTED' AND NOT EXISTS (SELECT 1 FROM refund_closure c WHERE c.attempt_id=r.attempt_id)", Integer.class)).isEqualTo(1);
            String insert = "INSERT INTO refund_closure(request_id,attempt_id,operator_subject,attribution,note,outcome,evidence_refunded_at,evidence_observed_at,closed_at) "
                    + "VALUES(?,?,?,?,?,?,'2026-11-08 01:00:00','2026-11-08 02:00:00','2026-11-08 02:00:00')";
            String command = id(); legacy.update(insert, command, open.getAttemptId(), "usr_admin", "OUR_REQUEST", "판정", "RECORDED");
            assertThatThrownBy(() -> legacy.update(insert, command, open.getAttemptId(), "usr_admin", "OUR_REQUEST", "판정", "RECORDED")).isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> legacy.update(insert, id(), open.getAttemptId(), "usr_admin", "OUR_REQUEST", "판정", "RECORDED")).isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> legacy.update(insert, id(), id(), "usr_admin", "OUR_REQUEST", "판정", "RECORDED")).isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> legacy.update(insert, id(), open.getAttemptId(), "usr_admin", "CONSOLE", "판정", "RECORDED")).isInstanceOf(DataAccessException.class).hasMessageContaining("chk_refund_closure_attribution");
            assertThatThrownBy(() -> legacy.update("UPDATE refund_closure SET note=' ' WHERE request_id=?", command)).isInstanceOf(DataAccessException.class).hasMessageContaining("chk_refund_closure_note");
            assertThatThrownBy(() -> legacy.update("UPDATE refund_closure SET closed_at='2026-11-08 01:59:59' WHERE request_id=?", command)).isInstanceOf(DataAccessException.class).hasMessageContaining("chk_refund_closure_time");
            assertThatThrownBy(() -> legacy.update("DELETE FROM refund_execution WHERE attempt_id=?", open.getAttemptId())).isInstanceOf(DataIntegrityViolationException.class);
            flyway.migrate(); assertThat(legacy.queryForObject("SELECT COUNT(*) FROM refund_closure", Integer.class)).isEqualTo(1);
        } finally { admin.execute("DROP DATABASE " + schema); }
    }

    private Payment paid() {
        Payment payment = Payment.receive(request(), "PORTONE_TEST");
        payment.confirm(new PgResult("PORTONE_TEST", payment.getPaymentId(), payment.getOrderNo(), payment.getAmount(), "KRW", "pg-" + id(), BASE.minusSeconds(60), PgResult.Decision.APPROVED, null, null, null), id(), BASE.minusSeconds(30));
        return payments.saveAndFlush(payment);
    }
    private RefundExecution prepared() {
        when(clock.instant()).thenReturn(BASE); RefundExecution execution = receive.execute(execution(paid())); prepare.execute(execution.getAttemptId()); return stored(execution);
    }
    private RefundExecution stored(RefundExecution execution) { return executions.findByAttemptId(execution.getAttemptId()).orElseThrow(); }
    private Payment payment(RefundExecution execution) { return payments.findByPaymentId(execution.getPaymentId()).orElseThrow(); }
    private PgRefundConfirmation confirmation(Payment payment, String cancellationId) {
        return new PgRefundConfirmation(PgRefundLookupRequest.from(payment), List.of(new RefundCancellation(cancellationId, payment.getAmount(), BASE.plusSeconds(30))));
    }
    private void confirm(RefundExecution execution, PgRefundConfirmation confirmation, Instant at) {
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            Payment payment = payments.lockByPaymentId(execution.getPaymentId()).orElseThrow();
            executions.lockByAttemptId(execution.getAttemptId()).orElseThrow().confirmRefund(payment, confirmation, id(), at);
        });
    }
    private long events(String type) { return jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE event_type=?", Long.class, type); }
    private double gauge() { return meters.get("stove.payment.refund.unattributed.open").gauge().value(); }
    private Map<String, List<Map<String, Object>>> snapshot() {
        Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
        for (String table : List.of("refund_closure", "refund_investigation", "refund_execution", "refund_cancellation_evidence", "purchase_payment", "processed_event", "outbox_event"))
            result.put(table, jdbc.queryForList("SELECT * FROM " + table));
        return result;
    }
    private String admin() { return token("usr_admin", "ADMIN"); }
    private String path(RefundExecution execution) { return ROOT + "/" + execution.getAttemptId(); }
    private Map<String, String> body(String command, String attribution, String note) {
        return Map.of("requestId", command, "attribution", attribution, "note", note, "operatorSubject", "forged", "outcome", "COMPLETED");
    }
    private Reply close(RefundExecution execution, String command, String attribution, String note) {
        return http("POST", path(execution) + "/closure", body(command, attribution, note), admin());
    }
    private static String code(Reply reply) { return reply.body().path("error").path("code").asText(); }
    private Reply http(String method, String path, Object body, String bearer) {
        RestClient.RequestBodySpec request = RestClient.create("http://127.0.0.1:" + port).method(HttpMethod.valueOf(method)).uri(path);
        if (bearer != null) request.header("Authorization", "Bearer " + bearer);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).body(body);
        return request.exchange((sent, reply) -> new Reply(reply.getStatusCode().value(), reply.getHeaders().getFirst("Location"), reply.getHeaders().getFirst("Cache-Control"), mapper.readTree(reply.getBody())));
    }
    private record Reply(int status, String location, String cacheControl, JsonNode body) { JsonNode data() { return body.path("data"); } }
}
