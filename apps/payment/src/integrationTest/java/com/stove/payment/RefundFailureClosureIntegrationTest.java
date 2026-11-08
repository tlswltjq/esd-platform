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
import static org.mockito.ArgumentMatchers.anyString;
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
import com.stove.payment.core.domain.PgRefundConfirmation;
import com.stove.payment.core.domain.PgRefundFailureConfirmation;
import com.stove.payment.core.domain.PgRefundLookupRequest;
import com.stove.payment.core.domain.PgRefundUncancelled;
import com.stove.payment.core.domain.PgResult;
import com.stove.payment.core.domain.PgUnavailable;
import com.stove.payment.core.domain.PortOneWebhookNotice;
import com.stove.payment.core.domain.RefundCancellation;
import com.stove.payment.core.domain.RefundCancellationClaim;
import com.stove.payment.core.domain.RefundCancellationDispatch;
import com.stove.payment.core.domain.RefundCancellationHold;
import com.stove.payment.core.domain.RefundCancellationHoldRepository;
import com.stove.payment.core.domain.RefundCancellationPermission;
import com.stove.payment.core.domain.RefundExecution;
import com.stove.payment.core.domain.RefundExecutionRepository;
import com.stove.payment.core.domain.RefundExecutionRequest;
import com.stove.payment.core.domain.RefundExecutionStatus;
import com.stove.payment.core.domain.RefundFailedCancellation;
import com.stove.payment.core.domain.RefundFailureClosureRepository;
import com.stove.payment.core.domain.RefundLookupInterval;
import com.stove.payment.core.port.RefundCancellationGateway;
import com.stove.payment.core.port.RefundPaymentLookup;
import com.stove.payment.core.service.ReadRefundDelays;
import com.stove.payment.core.service.ReceivePortOneWebhook;
import com.stove.payment.core.service.ReceiveRefundExecution;
import com.stove.payment.core.service.ReconcileRefundPayment;
import com.stove.payment.core.service.RecordRefundCancellationPreparation;
import com.stove.payment.core.service.RequestRefundCancellation;
import com.stove.payment.core.service.ResolveRefundLookup;
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
import java.util.concurrent.atomic.AtomicReference;
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
 * Decision 128: an operator closes a refund execution whose automatic POST stopped as failed only when the PG, re-inspected
 * at closure time, shows the original payment PAID with nothing cancelled and no requested or successful cancellation. The
 * failure is published as the existing {@code RefundExecutionFailed}; the lookup continues at the low post-failure frequency
 * (decisions 122·128) and a late full cancellation is raised for investigation only. Real HTTP, JWT and MySQL; the PortOne
 * lookup and cancellation gateway are contract fixtures.
 */
@TestPropertySource(properties = "stove.payment.pg.provider=PORTONE_TEST")
class RefundFailureClosureIntegrationTest extends PaymentDatabaseIntegrationTestBase {
    private static final String ROOT = "/api/v1/admin/refund-delays";
    private static final Instant BASE = Payment.now();
    private static final Instant DELAY = BASE.plusSeconds(10800);
    private static final String HASH = "d".repeat(64);

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
    @Autowired RefundFailureClosureRepository closures;
    @Autowired RefundCancellationHoldRepository holds;
    @Autowired ReadRefundDelays delays;
    @Autowired ReceiveRefundExecution receive;
    @Autowired RecordRefundCancellationPreparation prepare;
    @Autowired ReconcileRefundPayment reconcile;
    @Autowired RequestRefundCancellation cancellation;
    @Autowired ReceivePortOneWebhook webhook;
    @Autowired PlatformTransactionManager transactions;
    @Autowired MeterRegistry meters;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @MockitoBean RefundPaymentLookup lookup;
    @MockitoBean RefundCancellationGateway gateway;
    @MockitoBean Clock clock;
    @LocalServerPort int port;

    @BeforeEach @AfterEach void clear() {
        reset(lookup, gateway); when(clock.instant()).thenReturn(BASE);
        jdbc.update("DELETE FROM refund_failure_closure_cancellation"); jdbc.update("DELETE FROM refund_failure_closure");
        jdbc.update("DELETE FROM refund_closure");
        jdbc.update("DELETE FROM refund_investigation"); jdbc.update("DELETE FROM payment_pg_webhook");
        jdbc.update("UPDATE refund_execution SET active_cancel_hold_id=NULL"); jdbc.update("DELETE FROM refund_cancellation_hold");
        jdbc.update("DELETE FROM refund_execution ORDER BY id DESC");
        jdbc.update("DELETE FROM payment_order_lifecycle"); jdbc.update("DELETE FROM purchase_payment"); jdbc.update("DELETE FROM processed_event"); jdbc.update("DELETE FROM outbox_event");
    }

    @Test void aForbiddenHoldIsClosedAsFailedOnAnUncancelledPaymentAndPublishesTheExistingFailure() {
        RefundExecution execution = held(); Payment payment = payment(execution);
        // The worker's next lookup is far away; the closure re-inspects the PG directly, regardless of the lookup delay.
        jdbc.update("UPDATE refund_execution SET next_refund_lookup_at=? WHERE attempt_id=?", BASE.plusSeconds(600), execution.getAttemptId());
        when(lookup.inspectUncancelled(PgRefundLookupRequest.from(payment))).thenAnswer(call -> {
            when(clock.instant()).thenReturn(BASE.plusSeconds(62)); return Optional.of(uncancelled(payment));
        });
        when(clock.instant()).thenReturn(BASE.plusSeconds(61));
        assertThat(delays.count()).as("inside the window it is not delayed").isZero();
        String command = id();
        Reply closed = fail(execution, command, "403: 가맹점 권한 없음, 고객 재신청으로 안내");
        assertThat(closed.status()).isEqualTo(200); assertThat(closed.cacheControl()).isEqualTo("no-store");
        assertThat(closed.location()).isEqualTo(path(execution));
        JsonNode data = closed.data();
        assertThat(data.path("executionStatus").asText()).isEqualTo("FAILED");
        assertThat(data.path("failureConfirmedAt").asText()).isEqualTo(BASE.plusSeconds(62).toString());
        assertThat(data.path("failureClosure").path("evidence").asText()).isEqualTo("NO_LINKED_CANCELLATION");
        assertThat(data.path("failureClosure").path("stopReason").asText()).isEqualTo("CANCELLATION_HOLD");
        assertThat(data.path("failureClosure").path("operatorSubject").asText()).isEqualTo("usr_admin");
        assertThat(data.path("failureClosure").path("snapshotHash").asText()).isEqualTo(HASH);
        assertThat(data.path("failureClosure").path("failedCancellationIds").isEmpty()).isTrue();
        assertThat(data.path("failureClosure").path("evidenceObservedAt").asText()).isEqualTo(BASE.plusSeconds(62).toString());
        RefundExecution stored = stored(execution);
        assertThat(stored.getStatus()).isEqualTo(RefundExecutionStatus.FAILED); assertThat(stored.getFailureLookupHash()).isEqualTo(HASH);
        assertThat(stored.getRefundConfirmedAt()).isNull();
        assertThat(stored.getLookupSchedule().getNextAt()).as("still watched after the failure").isEqualTo(BASE.plusSeconds(600));
        assertThat(stored.getLookupSchedule().getLeaseUntil()).isNull(); assertThat(stored.getLookupSchedule().getClaimId()).isNull();
        // The existing failure notice, exactly once, so Order restores the license and allows the customer's retry.
        JsonNode event = failureEvent();
        assertThat(event.path("eventId").asText()).isEqualTo(stored.getFailureEventId());
        assertThat(event.path("attemptId").asText()).isEqualTo(execution.getAttemptId());
        assertThat(event.path("cancellationId").asText()).isEqualTo("ADMIN_FAILURE_CLOSURE:" + command);
        assertThat(event.path("lookupHash").asText()).isEqualTo(HASH);
        assertThat(events(EventType.REFUND_EXECUTION_FAILED)).isEqualTo(1);
        JsonNode detail = http("GET", path(execution), null, admin()).data();
        assertThat(detail.path("failureClosure").path("requestId").asText()).isEqualTo(command);
        assertThat(detail.path("executionStatus").asText()).isEqualTo("FAILED");
        verify(lookup, times(1)).inspectUncancelled(any()); verify(lookup, never()).find(any()); verify(lookup, never()).inspect(any(), any());
    }

    /** The same requestId returns the first response unchanged, even after a late full cancellation was recorded. */
    @Test void aReplayAfterALateCancellationReturnsTheFirstResponseAndChangedOrRepeatedCommandsConflict() {
        RefundExecution execution = prepared(); Payment payment = payment(execution);
        when(lookup.inspectUncancelled(PgRefundLookupRequest.from(payment))).thenReturn(Optional.of(uncancelled(payment)));
        when(clock.instant()).thenReturn(DELAY);
        String command = id(); Reply first = fail(execution, command, "3시간 창 종료, PG 취소 없음");
        assertThat(first.status()).isEqualTo(200);
        assertThat(first.data().path("failureClosure").path("stopReason").asText()).isEqualTo("WINDOW_ENDED");
        when(lookup.find(PgRefundLookupRequest.from(payment))).thenReturn(Optional.of(full(payment, "console-1", DELAY.plusSeconds(30))));
        when(clock.instant()).thenReturn(DELAY.plusSeconds(60)); worker().execute();
        assertThat(stored(execution).getLateCancellationObservedAt()).isEqualTo(DELAY.plusSeconds(60));
        when(clock.instant()).thenReturn(DELAY.plusSeconds(120));
        Reply replay = fail(execution, command, "3시간 창 종료, PG 취소 없음");
        assertThat(replay.status()).isEqualTo(200); assertThat(replay.data()).isEqualTo(first.data());
        Map<String, List<Map<String, Object>>> before = snapshot();
        assertThat(code(fail(execution, command, "다른 근거"))).isEqualTo("REFUND_CLOSURE_CHANGED");
        assertThat(code(http("POST", path(execution) + "/failure-closure", body(command, "3시간 창 종료, PG 취소 없음"), token("other_admin", "ADMIN"))))
                .isEqualTo("REFUND_CLOSURE_CHANGED");
        assertThat(code(fail(execution, id(), "두 번째 종결"))).isEqualTo("REFUND_NOT_FAILURE_CLOSABLE");
        assertThat(snapshot()).isEqualTo(before);
        assertThat(events(EventType.REFUND_EXECUTION_FAILED)).isEqualTo(1);
        verify(lookup, times(1)).inspectUncancelled(any());
    }

    /** Moved money (a cancelled amount, a requested or successful cancellation) is refused; the lease returns and nothing changes. */
    @Test void aPaymentThatMayHaveMovedIsRefusedTheLeaseReturnsAndTheWorkerScheduleIsKept() {
        RefundExecution execution = prepared();
        when(lookup.inspectUncancelled(any())).thenReturn(Optional.empty());
        when(clock.instant()).thenReturn(DELAY); Map<String, List<Map<String, Object>>> before = snapshot();
        Reply refused = fail(execution, id(), "콘솔 확인 전");
        assertThat(refused.status()).isEqualTo(409); assertThat(code(refused)).isEqualTo("REFUND_FAILURE_CLOSURE_PG_NOT_UNCANCELLED");
        assertThat(snapshot()).isEqualTo(before);
        assertThat(stored(execution).getLookupSchedule().getNextAt()).isEqualTo(BASE.plusSeconds(5));
        assertThat(delays.count()).isEqualTo(1);
        for (RuntimeException failure : List.of(new PgUnavailable("down"), new PgPaymentNotFound(), new IllegalArgumentException("mismatch"))) {
            reset(lookup); when(lookup.inspectUncancelled(any())).thenThrow(failure);
            String expected = failure instanceof IllegalArgumentException ? "CLOSURE_PG_INVALID_EVIDENCE" : "CLOSURE_PG_UNAVAILABLE";
            assertThat(code(fail(execution, id(), "조회"))).as(failure.getClass().getSimpleName()).isEqualTo(expected);
            assertThat(snapshot()).isEqualTo(before);
        }
    }

    /** Our own cancellation, when the PG returned one, must be listed FAILED; then it is the event's cancellation ID. */
    @Test void aLinkedFailedCancellationIsTheEvidenceAndAMissingOneIsInvalid() {
        RefundExecution execution = prepared(); Payment payment = payment(execution);
        PgRefundCancellationReceipt own = new PgRefundCancellationReceipt(PgRefundLookupRequest.from(payment), execution.getAttemptId(),
                "own-cancel", PgRefundCancellationReceipt.State.REQUESTED, BASE, 0, 0, "review");
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            Payment current = payments.lockByPaymentId(payment.getPaymentId()).orElseThrow();
            RefundExecution locked = executions.lockByAttemptId(execution.getAttemptId()).orElseThrow();
            locked.freezeCancellationBody("{\"reason\":\"review\"}");
            locked.recordCancellationReceipt(current, own, BASE.plusSeconds(1));
        });
        when(clock.instant()).thenReturn(DELAY);
        when(lookup.inspectUncancelled(any())).thenReturn(Optional.of(new PgRefundUncancelled(PgRefundLookupRequest.from(payment),
                List.of(new RefundFailedCancellation("console-failed", BASE, 0, 0, "console", null)), HASH)));
        Map<String, List<Map<String, Object>>> before = snapshot();
        assertThat(code(fail(execution, id(), "우리 취소 없음"))).isEqualTo("CLOSURE_PG_INVALID_EVIDENCE");
        assertThat(snapshot()).isEqualTo(before);
        when(lookup.inspectUncancelled(any())).thenReturn(Optional.of(new PgRefundUncancelled(PgRefundLookupRequest.from(payment),
                List.of(new RefundFailedCancellation("own-cancel", BASE, 0, 0, "review", null),
                        new RefundFailedCancellation("console-failed", BASE, 0, 0, "console", null)), HASH)));
        String command = id(); Reply closed = fail(execution, command, "우리 취소 FAILED 확인");
        assertThat(closed.status()).isEqualTo(200);
        assertThat(closed.data().path("failureClosure").path("evidence").asText()).isEqualTo("LINKED_CANCELLATION_FAILED");
        assertThat(closed.data().path("failureClosure").path("failedCancellationIds").toString()).isEqualTo("[\"console-failed\",\"own-cancel\"]");
        assertThat(failureEvent().path("cancellationId").asText()).isEqualTo("own-cancel");
        verify(lookup, never()).inspect(any(), any());
    }

    @Test void onlyStoppedUnconfirmedExecutionsCanBeClosedAndThePgIsNotCalledOtherwise() {
        RefundExecution running = prepared();
        when(clock.instant()).thenReturn(BASE); RefundExecution unprepared = receive.execute(execution(paid()));
        RefundExecution unattributed = prepared(); RefundExecution inFlight = prepared();
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            Payment payment = payments.lockByPaymentId(unattributed.getPaymentId()).orElseThrow();
            executions.lockByAttemptId(unattributed.getAttemptId()).orElseThrow().confirmRefund(payment, full(payment, "found-1", BASE.plusSeconds(30)), id(), BASE.plusSeconds(60));
            RefundExecution posting = executions.lockByAttemptId(inFlight.getAttemptId()).orElseThrow();
            posting.freezeCancellationBody("{\"reason\":\"review\"}");
            posting.getCancellationSchedule().claim(id(), DELAY.minusSeconds(1), Duration.ofSeconds(231), false);
        });
        when(clock.instant()).thenReturn(DELAY.minusNanos(1000));
        assertThat(code(fail(running, id(), "자동 송신 중"))).isEqualTo("REFUND_NOT_FAILURE_CLOSABLE");
        when(clock.instant()).thenReturn(DELAY.plusSeconds(1)); Map<String, List<Map<String, Object>>> before = snapshot();
        for (RefundExecution target : List.of(unprepared, unattributed, inFlight))
            assertThat(code(fail(target, id(), "대상 아님"))).isEqualTo("REFUND_NOT_FAILURE_CLOSABLE");
        assertThat(http("POST", ROOT + "/" + id() + "/failure-closure", body(id(), "없음"), admin()).status()).isEqualTo(404);
        assertThat(snapshot()).isEqualTo(before);
        verify(lookup, never()).inspectUncancelled(any());
    }

    @Test void aLiveWorkerLeaseRefusesTheClosureAndConcurrentClosuresHaveOneWinner() throws Exception {
        RefundExecution execution = prepared(); Payment payment = payment(execution);
        new TransactionTemplate(transactions).executeWithoutResult(tx ->
                executions.lockByAttemptId(execution.getAttemptId()).orElseThrow().getLookupSchedule().claim(id(), DELAY.minusSeconds(1), Duration.ofSeconds(60)));
        when(clock.instant()).thenReturn(DELAY);
        assertThat(code(fail(execution, id(), "작업자 조회 중"))).isEqualTo("REFUND_CLOSURE_BUSY");
        verify(lookup, never()).inspectUncancelled(any());
        when(clock.instant()).thenReturn(DELAY.plusSeconds(60));
        CountDownLatch inside = new CountDownLatch(1); CountDownLatch release = new CountDownLatch(1);
        when(lookup.inspectUncancelled(PgRefundLookupRequest.from(payment))).thenAnswer(call -> {
            inside.countDown(); assertThat(release.await(10, TimeUnit.SECONDS)).isTrue(); return Optional.of(uncancelled(payment));
        });
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Reply> first = pool.submit(() -> fail(execution, id(), "첫 운영자"));
            assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(code(fail(execution, id(), "둘째 운영자"))).isEqualTo("REFUND_CLOSURE_BUSY");
            release.countDown();
            assertThat(first.get(15, TimeUnit.SECONDS).status()).isEqualTo(200);
        } finally { release.countDown(); pool.shutdownNow(); }
        assertThat(closures.count()).isEqualTo(1); assertThat(events(EventType.REFUND_EXECUTION_FAILED)).isEqualTo(1);
    }

    /** A hold resumed while the PG was inspected makes the observation stale; nothing is saved. */
    @Test void aHoldResumedDuringTheLookupSavesNothing() {
        RefundExecution execution = held(); Payment payment = payment(execution);
        when(lookup.inspectUncancelled(PgRefundLookupRequest.from(payment))).thenAnswer(call -> {
            new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                RefundExecution locked = executions.lockByAttemptId(execution.getAttemptId()).orElseThrow();
                locked.resumeCancellation(locked.getActiveCancellationHoldId(), BASE.plusSeconds(61));
            });
            return Optional.of(uncancelled(payment));
        });
        when(clock.instant()).thenReturn(BASE.plusSeconds(61));
        assertThat(code(fail(execution, id(), "재개와 경합"))).isEqualTo("REFUND_NOT_FAILURE_CLOSABLE");
        assertThat(stored(execution).getStatus()).isEqualTo(RefundExecutionStatus.PENDING);
        assertThat(stored(execution).getLookupSchedule().getClaimId()).isNull();
        assertThat(closures.count()).isZero(); assertThat(events(EventType.REFUND_EXECUTION_FAILED)).isZero();
    }

    /**
     * Decisions 122·128: after the failure the worker keeps looking at min(10 min, max(60 s, age)); a webhook wakes it at
     * once; a late full cancellation is listed, counted and alerted, and changes neither status, money facts nor events.
     */
    @Test void aFailedExecutionIsWatchedAtLowFrequencyAndALateFullCancellationIsRaisedForInvestigationOnly() {
        RefundExecution execution = prepared(); Payment payment = payment(execution);
        when(lookup.inspectUncancelled(any())).thenReturn(Optional.of(uncancelled(payment)));
        when(clock.instant()).thenReturn(DELAY); assertThat(fail(execution, id(), "창 종료").status()).isEqualTo(200);
        long notices = jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event", Long.class);
        when(lookup.find(PgRefundLookupRequest.from(payment))).thenReturn(Optional.empty());
        when(clock.instant()).thenReturn(DELAY.plusSeconds(5)); worker().execute();
        assertThat(stored(execution).getLookupSchedule().getNextAt()).isEqualTo(DELAY.plusSeconds(5 + 600));
        when(clock.instant()).thenReturn(DELAY.plusSeconds(6)); worker().execute();
        verify(lookup, times(1)).find(any());
        // A cancellation webhook wakes the watched execution regardless of the 10-minute delay.
        webhook.execute(new PortOneWebhookNotice("msg-" + id(), "e".repeat(64), "Transaction.Cancelled", STORE, payment.getPaymentId(),
                "tx-" + id(), DELAY.plusSeconds(7)));
        PgRefundConfirmation late = full(payment, "console-1", DELAY.plusSeconds(6));
        when(lookup.find(PgRefundLookupRequest.from(payment))).thenReturn(Optional.of(late));
        when(clock.instant()).thenReturn(DELAY.plusSeconds(8)); worker().execute();
        verify(lookup, times(2)).find(any());
        RefundExecution stored = stored(execution);
        assertThat(stored.getStatus()).isEqualTo(RefundExecutionStatus.FAILED);
        assertThat(stored.getLateCancellationObservedAt()).isEqualTo(DELAY.plusSeconds(8));
        assertThat(stored.getLateCancelledAt()).isEqualTo(DELAY.plusSeconds(6));
        assertThat(stored.getRefundConfirmedAt()).isNull(); assertThat(stored.getConfirmedCancellations()).isEmpty();
        assertThat(stored.getLookupSchedule().getNextAt()).as("recorded once, then the lookup stops").isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event", Long.class)).as("no money or license event").isEqualTo(notices);
        assertThat(delays.lateCancellations()).isEqualTo(1);
        assertThat(meters.get("stove.payment.refund.failed.late_cancellation").gauge().value()).isEqualTo(1);
        Reply listed = http("GET", ROOT + "?kind=LATE_CANCELLATION", null, admin());
        assertThat(listed.status()).isEqualTo(200); assertThat(listed.data().path("totalElements").asInt()).isEqualTo(1);
        JsonNode item = listed.data().path("content").get(0);
        assertThat(item.path("attemptId").asText()).isEqualTo(execution.getAttemptId());
        assertThat(item.path("lateCancellationObservedAt").asText()).isEqualTo(DELAY.plusSeconds(8).toString());
        assertThat(item.path("failureClosure").path("stopReason").asText()).isEqualTo("WINDOW_ENDED");
    }

    /** Decision 127's closure re-inspects the PG directly too: a 10-minute lookup delay does not hold it back. */
    @Test void theDelayClosureIsNotBoundByTheLongLookupDelay() {
        RefundExecution execution = prepared(); Payment payment = payment(execution);
        when(clock.instant()).thenReturn(DELAY); worker(Duration.ofSeconds(60), Duration.ofMinutes(10)).execute();
        assertThat(stored(execution).getLookupSchedule().getNextAt()).isEqualTo(DELAY.plusSeconds(600));
        when(lookup.find(PgRefundLookupRequest.from(payment))).thenReturn(Optional.of(full(payment, "console-1", DELAY.plusSeconds(10))));
        when(clock.instant()).thenReturn(DELAY.plusSeconds(11));
        Reply closed = http("POST", path(execution) + "/closure", Map.of("requestId", id(), "attribution", "EXTERNAL_CANCELLATION", "note", "콘솔 취소"), admin());
        assertThat(closed.status()).isEqualTo(200);
        assertThat(stored(execution).getRefundConfirmedAt()).isEqualTo(DELAY.plusSeconds(11));
    }

    /** The predecessor closure's rows are what the retry's preflight expects; receiving the retry stops the predecessor's watch. */
    @Test void aCustomerRetryAfterTheFailureClosureIsVerifiedByTheClosureAndTakesOverTheLookup() {
        RefundExecution execution = prepared(); Payment payment = payment(execution);
        when(lookup.inspectUncancelled(any())).thenReturn(Optional.of(new PgRefundUncancelled(PgRefundLookupRequest.from(payment),
                List.of(new RefundFailedCancellation("console-failed", BASE, 0, 0, "console", null)), HASH)));
        when(clock.instant()).thenReturn(DELAY); assertThat(fail(execution, id(), "창 종료").status()).isEqualTo(200);
        RefundExecution failed = stored(execution);
        Instant retryAt = DELAY.plusSeconds(60); when(clock.instant()).thenReturn(retryAt);
        RefundExecutionRequest original = failed.request();
        RefundExecution retry = receive.execute(new RefundExecutionRequest(id(), retryAt, original.refundId(), id(), original.orderNo(),
                original.paymentId(), original.buyerSubject(), original.amount(), original.currency(), id(), original.attemptId()));
        assertThat(stored(execution).getLookupSchedule().getNextAt()).as("the retry observes the payment itself").isNull();
        when(gateway.prepareBody(any())).thenReturn("{\"reason\":\"ESD refund " + original.refundId() + "\"}");
        AtomicReference<RefundCancellationPermission> sent = new AtomicReference<>();
        when(gateway.send(any(), any(), anyString())).thenAnswer(call -> { sent.set(call.getArgument(0)); return RefundCancellationDispatch.UNCONFIRMED; });
        cancellation.executeFirst(retry.getAttemptId());
        RefundCancellationPermission permission = sent.get();
        assertThat(permission).as("the verified closure lets the retry send").isNotNull();
        assertThat(permission.predecessorVerified()).isTrue(); assertThat(permission.maySendAt(retryAt)).isTrue();
        assertThat(permission.previousFailures()).extracting(PgRefundCancellationReceipt::cancellationId).containsExactly("console-failed");
        assertThat(permission.previousFailures().getFirst().attemptId()).isEqualTo(execution.getAttemptId());
    }

    @Test void onlyAnAdministratorWithAValidCommandCanCloseAsFailed() {
        RefundExecution execution = prepared(); when(clock.instant()).thenReturn(DELAY); Map<String, List<Map<String, Object>>> before = snapshot();
        String closure = path(execution) + "/failure-closure";
        assertThat(http("POST", closure, body(id(), "확인"), null).status()).isEqualTo(401);
        for (String role : List.of("MEMBER", "CREATOR", "REVIEWER"))
            assertThat(http("POST", closure, body(id(), "확인"), token("usr_other", role)).status()).isEqualTo(403);
        for (Object malformed : List.of(Map.of(), body("bad", "확인"), body(id(), " "), body(id(), "x".repeat(501))))
            assertThat(http("POST", closure, malformed, admin()).status()).isEqualTo(400);
        assertThat(http("GET", ROOT + "?kind=LATE", null, admin()).status()).isEqualTo(400);
        for (String method : List.of("PUT", "PATCH", "DELETE")) assertThat(http(method, closure, body(id(), "확인"), admin()).status()).isEqualTo(403);
        assertThat(snapshot()).isEqualTo(before);
        verify(lookup, never()).inspectUncancelled(any());
    }

    /**
     * Decision 104: V40 keeps every V39 row. A row failed before it keeps its stopped lookup and NULL late columns and has no
     * failure closure; the relaxed failure check accepts a failure without a PG receipt; the audit keeps its constraints.
     */
    @Tag("migration")
    @Test void v40PreservesExistingExecutionsAndEnforcesTheFailureClosureConstraints() {
        RefundExecution failedBefore = prepared(); RefundExecution open = prepared();
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            Payment payment = payments.lockByPaymentId(failedBefore.getPaymentId()).orElseThrow();
            RefundExecution failing = executions.lockByAttemptId(failedBefore.getAttemptId()).orElseThrow();
            failing.freezeCancellationBody("{\"reason\":\"review\"}");
            PgRefundCancellationReceipt receipt = new PgRefundCancellationReceipt(PgRefundLookupRequest.from(payment), failing.getAttemptId(),
                    "failed", PgRefundCancellationReceipt.State.FAILED, BASE, 0, 0, "review");
            failing.recordCancellationReceipt(payment, receipt, BASE.plusSeconds(1));
            failing.confirmFailure(payment, new PgRefundFailureConfirmation(receipt, "a".repeat(64)), id(), BASE.plusSeconds(1));
            // V21 required a stopped lookup for a failure; the old row is stored as V39 would have it.
            failing.getLookupSchedule().stop();
        });
        MySQLContainer<?> mysql = SharedContainers.MYSQL;
        String schema = "refund_failure_closure_upgrade_" + id().replace("-", ""); String server = "jdbc:mysql://" + mysql.getHost() + ":" + mysql.getMappedPort(3306) + "/";
        String options = "?useSSL=false&allowPublicKeyRetrieval=true";
        JdbcTemplate admin = new JdbcTemplate(new DriverManagerDataSource(server + mysql.getDatabaseName() + options, "root", mysql.getPassword()));
        admin.execute("CREATE DATABASE " + schema);
        try {
            DriverManagerDataSource source = new DriverManagerDataSource(server + schema + options, "root", mysql.getPassword()); JdbcTemplate legacy = new JdbcTemplate(source);
            String location = "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath();
            Flyway.configure().dataSource(source).locations(location).target("39").load().migrate();
            List<String> tables = List.of("purchase_payment", "refund_execution", "refund_cancellation_evidence", "outbox_event");
            for (String table : tables) {
                List<String> columns = legacy.queryForList("SELECT column_name FROM information_schema.columns WHERE table_schema=? AND table_name=? AND generation_expression='' ORDER BY ordinal_position", String.class, schema, table);
                String copied = String.join(",", columns);
                legacy.update("INSERT INTO " + table + "(" + copied + ") SELECT " + copied + " FROM " + mysql.getDatabaseName() + "." + table);
            }
            // V39 refuses a failure that keeps its lookup or has no PG receipt.
            assertThatThrownBy(() -> legacy.update("UPDATE refund_execution SET next_refund_lookup_at=? WHERE attempt_id=?", BASE.plusSeconds(600), failedBefore.getAttemptId()))
                    .isInstanceOf(DataAccessException.class).hasMessageContaining("chk_refund_execution_failure");
            Map<String, List<Map<String, Object>>> before = new LinkedHashMap<>();
            for (String table : tables) before.put(table, legacy.queryForList("SELECT * FROM " + table));
            Flyway flyway = Flyway.configure().dataSource(source).locations(location).target("40").load(); flyway.migrate(); flyway.validate();
            for (String table : tables) {
                List<Map<String, Object>> after = legacy.queryForList("SELECT * FROM " + table);
                if (table.equals("refund_execution")) after.forEach(row -> {
                    assertThat(row.remove("late_cancel_observed_at")).isNull(); assertThat(row.remove("late_cancelled_at")).isNull();
                });
                assertThat(after).as(table).isEqualTo(before.get(table));
            }
            assertThat(legacy.queryForObject("SELECT COUNT(*) FROM refund_failure_closure", Integer.class)).isZero();
            // The old failure stays stopped: the worker's due filter (status PENDING or FAILED, a lookup deadline) skips it.
            assertThat(legacy.queryForObject("SELECT COUNT(*) FROM refund_execution WHERE status='FAILED' AND next_refund_lookup_at IS NOT NULL", Integer.class)).isZero();
            // The relaxed failure check: a watched failure without a PG receipt, as the operator closure writes it.
            legacy.update("UPDATE refund_execution SET status='FAILED', failure_event_id=?, failure_confirmed_at=?, failure_lookup_hash=? WHERE attempt_id=?",
                    id(), DELAY, HASH, open.getAttemptId());
            assertThatThrownBy(() -> legacy.update("UPDATE refund_execution SET failure_lookup_hash=NULL WHERE attempt_id=?", open.getAttemptId()))
                    .isInstanceOf(DataAccessException.class).hasMessageContaining("chk_refund_execution_failure_v40");
            assertThatThrownBy(() -> legacy.update("UPDATE refund_execution SET late_cancel_observed_at=? WHERE attempt_id=?", DELAY.plusSeconds(60), open.getAttemptId()))
                    .isInstanceOf(DataAccessException.class).hasMessageContaining("chk_refund_execution_late_cancel");
            String insert = "INSERT INTO refund_failure_closure(request_id,attempt_id,operator_subject,note,evidence,stop_reason,snapshot_hash,evidence_observed_at,"
                    + "closed_at,failure_event_id,failed_cancellation_id,execution_status,failure_confirmed_at) "
                    + "VALUES(?,?,?,?,?,?,?,'2026-11-08 02:00:00','2026-11-08 02:00:01',?,?,'FAILED','2026-11-08 02:00:01')";
            String command = id(); String event = id();
            legacy.update(insert, command, open.getAttemptId(), "usr_admin", "근거", "NO_LINKED_CANCELLATION", "WINDOW_ENDED", HASH, event, "ADMIN_FAILURE_CLOSURE:" + command);
            assertThatThrownBy(() -> legacy.update(insert, command, open.getAttemptId(), "usr_admin", "근거", "NO_LINKED_CANCELLATION", "WINDOW_ENDED", HASH, id(), "x"))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> legacy.update(insert, id(), open.getAttemptId(), "usr_admin", "근거", "NO_LINKED_CANCELLATION", "WINDOW_ENDED", HASH, id(), "x"))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> legacy.update(insert, id(), id(), "usr_admin", "근거", "NO_LINKED_CANCELLATION", "WINDOW_ENDED", HASH, id(), "x"))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> legacy.update(insert, id(), failedBefore.getAttemptId(), "usr_admin", "근거", "CONSOLE", "WINDOW_ENDED", HASH, id(), "x"))
                    .isInstanceOf(DataAccessException.class).hasMessageContaining("chk_refund_failure_closure_evidence");
            assertThatThrownBy(() -> legacy.update(insert, id(), failedBefore.getAttemptId(), "usr_admin", "근거", "NO_LINKED_CANCELLATION", "LATER", HASH, id(), "x"))
                    .isInstanceOf(DataAccessException.class).hasMessageContaining("chk_refund_failure_closure_stop");
            assertThatThrownBy(() -> legacy.update("UPDATE refund_failure_closure SET execution_status='PENDING' WHERE request_id=?", command))
                    .isInstanceOf(DataAccessException.class).hasMessageContaining("chk_refund_failure_closure_receipt");
            assertThatThrownBy(() -> legacy.update("UPDATE refund_failure_closure SET note=' ' WHERE request_id=?", command))
                    .isInstanceOf(DataAccessException.class).hasMessageContaining("chk_refund_failure_closure_note");
            assertThatThrownBy(() -> legacy.update("UPDATE refund_failure_closure SET closed_at='2026-11-08 01:59:59' WHERE request_id=?", command))
                    .isInstanceOf(DataAccessException.class).hasMessageContaining("chk_refund_failure_closure_time");
            assertThatThrownBy(() -> legacy.update("DELETE FROM refund_execution WHERE attempt_id=?", open.getAttemptId())).isInstanceOf(DataIntegrityViolationException.class);
            flyway.migrate(); assertThat(legacy.queryForObject("SELECT COUNT(*) FROM refund_failure_closure", Integer.class)).isEqualTo(1);
        } finally { admin.execute("DROP DATABASE " + schema); }
    }

    private ResolveRefundLookup worker() { return worker(Duration.ofSeconds(60), Duration.ofMinutes(10)); }
    private ResolveRefundLookup worker(Duration initial, Duration max) {
        return new ResolveRefundLookup(payments, executions, reconcile, transactions, clock, Runnable::run, Duration.ofSeconds(97), 20,
                new RefundLookupInterval(initial, max));
    }
    private Payment paid() {
        Payment payment = Payment.receive(request(), "PORTONE_TEST");
        payment.confirm(new PgResult("PORTONE_TEST", payment.getPaymentId(), payment.getOrderNo(), payment.getAmount(), "KRW", "pg-" + id(), BASE.minusSeconds(60), PgResult.Decision.APPROVED, null, null, null), id(), BASE.minusSeconds(30));
        return payments.saveAndFlush(payment);
    }
    private RefundExecution prepared() {
        when(clock.instant()).thenReturn(BASE); RefundExecution execution = receive.execute(execution(paid())); prepare.execute(execution.getAttemptId()); return stored(execution);
    }
    /** A 403 rejection hold inside the original window (R7.15), as the dispatch records it. */
    private RefundExecution held() {
        RefundExecution execution = prepared();
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            RefundExecution current = executions.lockByAttemptId(execution.getAttemptId()).orElseThrow();
            current.freezeCancellationBody("{\"reason\":\"review\"}");
            RefundCancellationClaim claim = current.getCancellationSchedule().claim(id(), BASE, Duration.ofSeconds(231), false);
            RefundCancellationHold hold = holds.saveAndFlush(RefundCancellationHold.forbidden(current.getAttemptId(), claim, BASE.plusSeconds(1), true));
            current.holdCancellation(hold.getHoldId(), claim);
        });
        return stored(execution);
    }
    private RefundExecution stored(RefundExecution execution) { return executions.findByAttemptId(execution.getAttemptId()).orElseThrow(); }
    private Payment payment(RefundExecution execution) { return payments.findByPaymentId(execution.getPaymentId()).orElseThrow(); }
    private PgRefundUncancelled uncancelled(Payment payment) { return new PgRefundUncancelled(PgRefundLookupRequest.from(payment), List.of(), HASH); }
    private PgRefundConfirmation full(Payment payment, String cancellationId, Instant at) {
        return new PgRefundConfirmation(PgRefundLookupRequest.from(payment), List.of(new RefundCancellation(cancellationId, payment.getAmount(), at)));
    }
    private long events(String type) { return jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE event_type=?", Long.class, type); }
    private JsonNode failureEvent() {
        try { return mapper.readTree(jdbc.queryForObject("SELECT payload FROM outbox_event WHERE event_type=?", String.class, EventType.REFUND_EXECUTION_FAILED)); }
        catch (Exception invalid) { throw new IllegalStateException(invalid); }
    }
    private Map<String, List<Map<String, Object>>> snapshot() {
        Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
        for (String table : List.of("refund_failure_closure", "refund_failure_closure_cancellation", "refund_closure", "refund_execution",
                "refund_cancellation_evidence", "refund_cancellation_hold", "purchase_payment", "processed_event", "outbox_event"))
            result.put(table, jdbc.queryForList("SELECT * FROM " + table));
        return result;
    }
    private String admin() { return token("usr_admin", "ADMIN"); }
    private String path(RefundExecution execution) { return ROOT + "/" + execution.getAttemptId(); }
    private Map<String, String> body(String command, String note) {
        return Map.of("requestId", command, "note", note, "operatorSubject", "forged", "executionStatus", "PENDING");
    }
    private Reply fail(RefundExecution execution, String command, String note) {
        return http("POST", path(execution) + "/failure-closure", body(command, note), admin());
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
