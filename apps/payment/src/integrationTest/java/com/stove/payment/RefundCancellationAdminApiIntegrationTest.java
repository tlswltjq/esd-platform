package com.stove.payment;

import static com.stove.payment.PaymentFixtures.id;
import static com.stove.payment.PaymentFixtures.request;
import static com.stove.payment.RefundFixtures.execution;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.payment.core.domain.Payment;
import com.stove.payment.core.domain.PaymentRepository;
import com.stove.payment.core.domain.PaymentStatus;
import com.stove.payment.core.domain.PgResult;
import com.stove.payment.core.domain.RefundCancellationClaim;
import com.stove.payment.core.domain.RefundCancellationHold;
import com.stove.payment.core.domain.RefundCancellationHoldRepository;
import com.stove.payment.core.domain.RefundExecution;
import com.stove.payment.core.domain.RefundExecutionRepository;
import com.stove.payment.core.service.ReceiveRefundExecution;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

/** HTTP/JWT/MySQL verification; financial and suspension evidence use existing domain fixtures. */
class RefundCancellationAdminApiIntegrationTest extends PaymentIntegrationTestBase {
    private static final String ROOT = "/api/v1/admin/refund-cancellation-holds";
    private static final Instant BASE = Payment.now();
    @MockitoSpyBean PaymentRepository payments;
    @MockitoSpyBean RefundExecutionRepository executions;
    @MockitoSpyBean RefundCancellationHoldRepository holds;
    @Autowired ReceiveRefundExecution receive;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired EntityManager entityManager;
    @MockitoBean Clock clock;
    @LocalServerPort int port;

    @BeforeEach @AfterEach void clear() {
        reset(payments, executions, holds); when(clock.instant()).thenReturn(BASE);
        jdbc.update("DELETE FROM payment_pg_webhook"); jdbc.update("DELETE FROM refund_execution ORDER BY id DESC");
        jdbc.update("DELETE FROM payment_order_lifecycle"); jdbc.update("DELETE FROM purchase_payment"); jdbc.update("DELETE FROM processed_event"); jdbc.update("DELETE FROM outbox_event");
    }

    @Test void currentHoldsArePagedDeterministicallyAndDetailShowsReasonDeadlineAndEligibility() {
        Fixture first = held(false); Fixture second = held(true);
        Map<String, List<Map<String, Object>>> before = snapshot();
        Response list = http("GET", ROOT + "?size=1", null, admin());
        assertThat(list.status()).isEqualTo(200); assertThat(list.cacheControl()).isEqualTo("no-store");
        assertThat(list.data().path("totalElements").asInt()).isEqualTo(2);
        assertThat(list.data().path("content").get(0).path("holdId").asText()).isEqualTo(first.hold().getHoldId());
        assertThat(http("GET", ROOT + "?size=1&page=1", null, admin()).data().path("content").get(0).path("holdId").asText()).isEqualTo(second.hold().getHoldId());
        Response detail = detail(second);
        assertThat(detail.status()).isEqualTo(200); assertThat(detail.cacheControl()).isEqualTo("no-store");
        assertThat(detail.data().path("reason").asText()).isEqualTo("PG_REQUEST_INVALID");
        assertThat(detail.data().path("current").asBoolean()).isTrue(); assertThat(detail.data().path("resumable").asBoolean()).isTrue();
        assertThat(detail.data().path("resumeDeadlineAt").asText()).isEqualTo(BASE.plusSeconds(10800).toString());
        assertThat(detail.data().path("amount").asLong()).isEqualTo(second.execution().getAmount());
        for (String hidden : List.of("cancellationRequestBody", "claimId", "buyerSubject", "apiSecret", "idempotencyKey"))
            assertThat(detail.data().has(hidden)).as(hidden).isFalse();
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test void administratorResumeStoresJwtAuditAndOneReceiptWithoutCreatingANewExecutionOrOutbox() {
        Fixture fixture = held(false); String command = id();
        String originalBody = stored(fixture).getCancellationRequestBody();
        Response first = resume(fixture, command); Response duplicate = resume(fixture, command);
        assertThat(first.status()).isEqualTo(200); assertThat(duplicate.data()).isEqualTo(first.data());
        assertThat(first.location()).isEqualTo(path(fixture)); assertThat(first.cacheControl()).isEqualTo("no-store");
        assertThat(first.data().path("attemptId").asText()).isEqualTo(fixture.execution().getAttemptId());
        assertThat(first.data().path("receivedAt").asText()).isEqualTo(BASE.plusSeconds(10).toString());
        RefundExecution current = stored(fixture);
        assertThat(current.getActiveCancellationHoldId()).isNull(); assertThat(current.getCancellationSchedule().getNextAt()).isEqualTo(BASE.plusSeconds(10));
        assertThat(current.getFirstCancellationPreparedAt()).isEqualTo(BASE); assertThat(current.getCancellationRequestBody()).isEqualTo(originalBody);
        assertThat(current.getLookupSchedule().getNextAt()).isEqualTo(fixture.execution().getLookupSchedule().getNextAt());
        assertThat(current.request()).isEqualTo(fixture.execution().request()); assertThat(executions.count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event", Integer.class)).isZero();
        assertThat(payments.findByPaymentId(current.getPaymentId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.PAID);
        JsonNode detail = detail(fixture).data();
        assertThat(detail.path("operatorSubject").asText()).isEqualTo("usr_admin"); assertThat(detail.path("note").asText()).isEqualTo("PG 설정 확인");
        assertThat(detail.path("resume")).isEqualTo(first.data()); assertThat(detail.path("current").asBoolean()).isFalse();
        assertThat(detail.path("resumable").asBoolean()).isFalse();
        assertThat(http("GET", ROOT, null, admin()).data().path("totalElements").asInt()).isZero();
        assertThat(http("GET", ROOT + "?activeOnly=false", null, admin()).data().path("totalElements").asInt()).isEqualTo(1);
    }

    @Test void concurrentSameInstructionsConvergeAndDifferentInstructionsCannotRewriteAudit() throws Exception {
        Fixture fixture = held(false); String command = id(); CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Response> first = pool.submit(() -> { start.await(); return resume(fixture, command); });
            Future<Response> second = pool.submit(() -> { start.await(); return resume(fixture, command); });
            start.countDown(); Response one = first.get(15, TimeUnit.SECONDS); Response two = second.get(15, TimeUnit.SECONDS);
            assertThat(one.status()).isEqualTo(200); assertThat(two.status()).isEqualTo(200); assertThat(one.data()).isEqualTo(two.data());
        } finally { start.countDown(); pool.shutdownNow(); }
        Map<String, List<Map<String, Object>>> before = snapshot();
        assertThat(resume(fixture, id()).status()).isEqualTo(409);
        assertThat(http("POST", path(fixture) + "/resume", body(fixture, command, "다른 사유"), admin()).status()).isEqualTo(409);
        assertThat(http("POST", path(fixture) + "/resume", body(fixture, command, "PG 설정 확인"), token("another-admin", "ADMIN")).status()).isEqualTo(409);
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test void oldReceiptCannotReleaseANewHoldAndRequiresANewExplicitInstruction() {
        Fixture fixture = held(false); String command = id(); Response first = resume(fixture, command);
        RefundCancellationHold next = new TransactionTemplate(transactions).execute(tx -> {
            RefundExecution execution = executions.lockByAttemptId(fixture.execution().getAttemptId()).orElseThrow();
            RefundCancellationClaim claim = execution.getCancellationSchedule().claim(id(), BASE.plusSeconds(20), Duration.ofSeconds(231), false);
            RefundCancellationHold hold = holds.saveAndFlush(RefundCancellationHold.invalidRequest(execution.getAttemptId(), claim, BASE.plusSeconds(21), true));
            execution.holdCancellation(hold.getHoldId(), claim); executions.flush(); return hold;
        });
        when(clock.instant()).thenReturn(BASE.plusSeconds(30)); Map<String, List<Map<String, Object>>> before = snapshot();
        assertThat(resume(fixture, command).data()).isEqualTo(first.data()); assertThat(snapshot()).isEqualTo(before);
        assertThat(resume(fixture, id()).status()).isEqualTo(409); assertThat(stored(fixture).getActiveCancellationHoldId()).isEqualTo(next.getHoldId());
        Fixture current = new Fixture(fixture.execution(), next);
        assertThat(resume(current, id()).status()).isEqualTo(200); assertThat(stored(fixture).getFirstCancellationPreparedAt()).isEqualTo(BASE);
    }

    @Test void deadlineExpiresExactlyAtThreeHoursButAnAlreadyStoredReceiptRemainsReadable() {
        Fixture fixture = held(false); when(clock.instant()).thenReturn(BASE.plusSeconds(10800));
        Map<String, List<Map<String, Object>>> before = snapshot();
        assertThat(detail(fixture).data().path("current").asBoolean()).isTrue();
        assertThat(detail(fixture).data().path("resumable").asBoolean()).isFalse();
        assertThat(resume(fixture, id()).status()).isEqualTo(409); assertThat(snapshot()).isEqualTo(before);
        when(clock.instant()).thenReturn(BASE.plusSeconds(10799)); String command = id(); Response accepted = resume(fixture, command);
        assertThat(accepted.status()).isEqualTo(200); when(clock.instant()).thenReturn(BASE.plusSeconds(10800));
        assertThat(resume(fixture, command).data()).isEqualTo(accepted.data());
        assertThat(stored(fixture).getFirstCancellationPreparedAt()).isEqualTo(BASE);
    }

    @Test void invalidInputsRolesTokensAndMissingTargetsDoNotChangeAnyRecords() {
        Fixture fixture = held(false); Map<String, List<Map<String, Object>>> before = snapshot(); Map<String, String> body = body(fixture, id(), "PG 설정 확인");
        for (String path : List.of(ROOT, path(fixture))) {
            assertThat(http("GET", path, null, null).status()).isEqualTo(401);
            for (String role : List.of("MEMBER", "CREATOR", "REVIEWER")) assertThat(http("GET", path, null, token("usr_buyer", role)).status()).isEqualTo(403);
        }
        assertThat(http("POST", path(fixture) + "/resume", body, null).status()).isEqualTo(401);
        for (String role : List.of("MEMBER", "CREATOR", "REVIEWER"))
            assertThat(http("POST", path(fixture) + "/resume", body, token("usr_buyer", role)).status()).isEqualTo(403);
        assertThat(http("POST", path(fixture) + "/resume", body, token("usr_admin", "ADMIN", ISSUER, "wrong", Instant.now().plusSeconds(60))).status()).isEqualTo(401);
        assertThat(http("POST", path(fixture) + "/resume", body, token("usr_admin", "ADMIN", ISSUER, "esd-api", Instant.now().minusSeconds(120))).status()).isEqualTo(401);
        for (String suffix : List.of("?page=-1", "?size=101", "?size=0", "?activeOnly=invalid"))
            assertThat(http("GET", ROOT + suffix, null, admin()).status()).isEqualTo(400);
        assertThat(http("GET", ROOT + "/invalid", null, admin()).status()).isEqualTo(400);
        assertThat(http("GET", ROOT + "/" + id(), null, admin()).status()).isEqualTo(404);
        assertThat(http("POST", ROOT + "/" + id() + "/resume", body, admin()).status()).isEqualTo(404);
        assertThat(http("POST", path(fixture) + "/resume", Map.of("requestId", id(), "attemptId", id(), "note", "확인"), admin()).status()).isEqualTo(404);
        for (Object malformed : List.of(Map.of(), Map.of("requestId", "bad", "attemptId", fixture.execution().getAttemptId(), "note", "확인"),
                body(fixture, id(), " "), body(fixture, id(), "x".repeat(501))))
            assertThat(http("POST", path(fixture) + "/resume", malformed, admin()).status()).isEqualTo(400);
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test void flushFailureRollsBackTheScheduleAndAuditThenTheSameCommandCanRecover() {
        Fixture fixture = held(false); String command = id(); Map<String, List<Map<String, Object>>> before = snapshot();
        new TransactionTemplate(transactions).executeWithoutResult(tx -> doAnswer(call -> {
            entityManager.flush();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refund_cancellation_hold WHERE resume_request_id=?", Integer.class, command)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refund_execution WHERE active_cancel_hold_id IS NULL AND next_cancel_retry_at IS NOT NULL", Integer.class)).isEqualTo(1);
            throw new IllegalStateException("after real resume flush");
        }).when(executions).flush());
        try {
            assertThat(resume(fixture, command).status()).isEqualTo(500); assertThat(snapshot()).isEqualTo(before);
        } finally { reset(executions); }
        assertThat(resume(fixture, command).status()).isEqualTo(200);
    }

    @Test void waitingForThePaymentLockMustNotOverwriteANewerBalanceStop() throws Exception {
        Fixture fixture = held(false); CountDownLatch attemptingLock = new CountDownLatch(1); ExecutorService pool = Executors.newSingleThreadExecutor();
        Long paymentRowId = payments.findByPaymentId(fixture.execution().getPaymentId()).orElseThrow().getId();
        try {
            Future<Response> future = new TransactionTemplate(transactions).execute(tx -> {
                payments.lockByPaymentId(fixture.execution().getPaymentId()).orElseThrow();
                doAnswer(call -> {
                    attemptingLock.countDown();
                    return Optional.ofNullable(entityManager.find(Payment.class, paymentRowId, LockModeType.PESSIMISTIC_WRITE));
                }).when(payments).lockByPaymentId(fixture.execution().getPaymentId());
                Future<Response> request = pool.submit(() -> resume(fixture, id())); await(attemptingLock);
                RefundExecution locked = executions.lockByAttemptId(fixture.execution().getAttemptId()).orElseThrow();
                locked.blockAutomaticCancellation(BASE.plusSeconds(5)); executions.flush(); return request;
            });
            assertThat(future.get(15, TimeUnit.SECONDS).status()).isEqualTo(409);
            assertThat(stored(fixture).getCancellationBalanceChangedAt()).isEqualTo(BASE.plusSeconds(5));
            assertThat(stored(fixture).getActiveCancellationHoldId()).isEqualTo(fixture.hold().getHoldId());
            assertThat(holds.findByHoldId(fixture.hold().getHoldId()).orElseThrow().getResumeRequestId()).isNull();
            assertThat(detail(fixture).data().path("resumable").asBoolean()).isFalse();
        } finally { reset(payments); pool.shutdownNow(); }
    }

    @Test void concurrentReuseOfOneCommandAcrossTwoHoldsHasOneConflictAndNoPartialLoser() throws Exception {
        Fixture first = held(false); Fixture second = held(true); String command = id(); CountDownLatch inspected = new CountDownLatch(2);
        doAnswer(call -> {
            Optional<RefundCancellationHold> result = entityManager.createQuery(
                    "select h from RefundCancellationHold h where h.resumeRequestId=:requestId", RefundCancellationHold.class)
                    .setParameter("requestId", command).getResultStream().findFirst();
            inspected.countDown(); await(inspected); return result;
        })
                .when(holds).findByResumeRequestId(command);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Response> one = pool.submit(() -> resume(first, command)); Future<Response> two = pool.submit(() -> resume(second, command));
            assertThat(List.of(one.get(15, TimeUnit.SECONDS).status(), two.get(15, TimeUnit.SECONDS).status())).containsExactlyInAnyOrder(200, 409);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refund_cancellation_hold WHERE resume_request_id IS NOT NULL", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refund_execution WHERE active_cancel_hold_id IS NOT NULL", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refund_execution WHERE next_cancel_retry_at IS NOT NULL", Integer.class)).isEqualTo(1);
        } finally { reset(holds); pool.shutdownNow(); }
    }

    @Test void anotherAttemptsHoldCannotBeResumedAndSequentialCommandReuseIsAConflict() {
        Fixture first = held(false); Fixture second = held(true); String command = id(); Map<String, List<Map<String, Object>>> before = snapshot();
        assertThat(http("POST", path(first) + "/resume", body(second, command, "PG 설정 확인"), admin()).status()).isEqualTo(409);
        assertThat(snapshot()).isEqualTo(before); assertThat(resume(first, command).status()).isEqualTo(200);
        before = snapshot(); assertThat(resume(second, command).status()).isEqualTo(409); assertThat(snapshot()).isEqualTo(before);
    }

    private Fixture held(boolean invalid) {
        when(clock.instant()).thenReturn(BASE);
        Payment paid = Payment.receive(request(), "PORTONE_TEST");
        paid.confirm(new PgResult("PORTONE_TEST", paid.getPaymentId(), paid.getOrderNo(), paid.getAmount(), "KRW", "pg-" + id(),
                BASE.minusSeconds(60), PgResult.Decision.APPROVED, null, null, null), id(), BASE.minusSeconds(30));
        payments.saveAndFlush(paid); RefundExecution received = receive.execute(execution(paid));
        RefundCancellationHold hold = new TransactionTemplate(transactions).execute(tx -> {
            RefundExecution locked = executions.lockByAttemptId(received.getAttemptId()).orElseThrow();
            locked.prepareCancellation(BASE); locked.freezeCancellationBody("{\"amount\":10000,\"currentCancellableAmount\":10000,\"reason\":\"review\"}");
            RefundCancellationClaim claim = locked.getCancellationSchedule().claim(id(), BASE, Duration.ofSeconds(231), false);
            RefundCancellationHold observation = invalid ? RefundCancellationHold.invalidRequest(locked.getAttemptId(), claim, BASE.plusSeconds(1), true)
                    : RefundCancellationHold.forbidden(locked.getAttemptId(), claim, BASE.plusSeconds(1), true);
            holds.saveAndFlush(observation); locked.holdCancellation(observation.getHoldId(), claim); executions.flush(); return observation;
        });
        when(clock.instant()).thenReturn(BASE.plusSeconds(10)); return new Fixture(stored(received.getAttemptId()), hold);
    }
    private RefundExecution stored(Fixture fixture) { return stored(fixture.execution().getAttemptId()); }
    private RefundExecution stored(String attemptId) { return executions.findByAttemptId(attemptId).orElseThrow(); }
    private Map<String, List<Map<String, Object>>> snapshot() {
        Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
        for (String table : List.of("refund_cancellation_hold", "refund_execution", "purchase_payment", "processed_event", "outbox_event"))
            result.put(table, jdbc.queryForList("SELECT * FROM " + table + " ORDER BY id"));
        return result;
    }
    private static void await(CountDownLatch latch) {
        try { assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue(); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
    }
    private String admin() { return token("usr_admin", "ADMIN"); }
    private String path(Fixture fixture) { return ROOT + "/" + fixture.hold().getHoldId(); }
    private Map<String, String> body(Fixture fixture, String command, String note) {
        return Map.of("requestId", command, "attemptId", fixture.execution().getAttemptId(), "note", note, "operatorSubject", "forged");
    }
    private Response detail(Fixture fixture) { return http("GET", path(fixture), null, admin()); }
    private Response resume(Fixture fixture, String command) { return http("POST", path(fixture) + "/resume", body(fixture, command, "PG 설정 확인"), admin()); }
    private Response http(String method, String path, Object body, String bearer) {
        RestClient.RequestBodySpec request = RestClient.create("http://127.0.0.1:" + port).method(HttpMethod.valueOf(method)).uri(path);
        if (bearer != null) request.header("Authorization", "Bearer " + bearer);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).body(body);
        return request.exchange((sent, reply) -> new Response(reply.getStatusCode().value(), reply.getHeaders().getFirst("Location"),
                reply.getHeaders().getFirst("Cache-Control"), mapper.readTree(reply.getBody())));
    }
    private record Fixture(RefundExecution execution, RefundCancellationHold hold) { }
    private record Response(int status, String location, String cacheControl, JsonNode body) { JsonNode data() { return body.path("data"); } }
}
