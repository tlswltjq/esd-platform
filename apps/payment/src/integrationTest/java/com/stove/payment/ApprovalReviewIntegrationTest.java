package com.stove.payment;

import static com.stove.payment.PaymentFixtures.id;
import static com.stove.payment.PaymentFixtures.request;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.payment.core.domain.ApprovalObservation;
import com.stove.payment.core.domain.ApprovalToken;
import com.stove.payment.core.domain.OrderAdmissionReceipt;
import com.stove.payment.core.domain.PgResult;
import com.stove.payment.core.domain.ApprovalReviewReason;
import com.stove.payment.core.domain.PaymentApprovalStatus;
import com.stove.payment.core.domain.Payment;
import com.stove.payment.core.domain.PaymentApproval;
import com.stove.payment.core.domain.PaymentApprovalInput;
import com.stove.payment.core.domain.PaymentApprovalRepository;
import com.stove.payment.core.domain.PaymentOrderLifecycleRepository;
import com.stove.payment.core.domain.PaymentRepository;
import com.stove.payment.core.port.ApprovalPayloadProtection;
import com.stove.payment.core.port.OrderPaymentAdmissions;
import com.stove.payment.core.port.PgPaymentApprovalGateway;
import com.stove.payment.core.service.ResolvePayment;
import com.stove.payment.core.service.ResolvePaymentApproval;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.payment.core.domain.ApprovalInvestigationRequest;
import com.stove.payment.core.domain.ApprovalInvestigationView;
import com.stove.payment.core.service.RecordApprovalInvestigation;
import com.stove.common.test.PausePoints;
import com.stove.common.test.PausePoints.Gate;
import com.stove.common.test.PausePoints.Run;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.junit.jupiter.api.AfterEach;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.http.MediaType;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.client.RestClient;

@AutoConfigureObservability
class ApprovalReviewIntegrationTest extends PaymentIntegrationTestBase {
    static final PausePoints POINTS = new PausePoints();
    @TestConfiguration static class Pausing {
        @Bean static BeanPostProcessor pausing() {
            return PausePoints.pausing(POINTS, PaymentApprovalRepository.class);
        }
    }

    private static final String ROOT = "/api/v1/admin/approval-reviews";
    @Autowired PaymentRepository payments;
    @Autowired RecordApprovalInvestigation record;
    @MockitoSpyBean PaymentApprovalRepository approvals;
    @Autowired PaymentOrderLifecycleRepository lifecycle;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ObjectMapper mapper;
    @Autowired PrometheusMeterRegistry prometheus;
    @Autowired JdbcTemplate jdbc;
    @LocalServerPort int port;

    @AfterEach void releaseGates() {
        POINTS.reset();
        // The investigation audit is not cascaded from its approval (V38), so other classes that delete
        // purchases must not find rows left by this class.
        jdbc.update("DELETE FROM approval_investigation");
    }

    /** The append-only investigation record is audit evidence: deleting its approval or purchase must not erase it. */
    @Test void theInvestigationAuditCannotBeErasedByDeletingItsApprovalOrPurchase() {
        PaymentApproval held = held("PG_INVALID_EVIDENCE");
        Response note = http("POST", ROOT + "/" + held.getApprovalId() + "/investigations",
                Map.of("requestId", id(), "note", "audit kept"), "ADMIN");
        assertThat(note.status()).isEqualTo(200);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM payment_approval WHERE approval_id=?", held.getApprovalId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM purchase_payment WHERE payment_id=?", held.getPaymentId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM approval_investigation WHERE approval_id=?",
                Integer.class, held.getApprovalId())).isEqualTo(1);
    }

    @Test void dbOnlyListDetailAndAppendUseJwtActorAndHideSecrets() {
        PaymentApproval held = held("PG_INVALID_EVIDENCE");
        Response list = http("GET", ROOT + "?reason=PG_INVALID_EVIDENCE", null, "ADMIN");
        assertThat(list.status()).isEqualTo(200);
        assertThat(list.cache()).isEqualTo("no-store");
        assertThat(allListed("PG_INVALID_EVIDENCE")).contains(held.getApprovalId()).doesNotContain("protectedPayload", "token", "ciphertext");
        assertThat(http("GET", ROOT + "/" + held.getApprovalId(), null, "ADMIN").status()).isEqualTo(200);
        String path = ROOT + "/" + held.getApprovalId() + "/investigations";
        for (int n = 0; n < 2; n++) {
            Response note = http("POST", path, Map.of("requestId", id(), "note", "checked", "operatorSubject", "forged"), "ADMIN");
            assertThat(note.status()).isEqualTo(200);
            assertThat(note.body().path("data").path("operatorSubject").asText()).isEqualTo("usr_operator");
        }
        assertThat(http("GET", path, null, "ADMIN").body().path("data").path("totalElements").asInt()).isEqualTo(2);
        assertThat(gauge("PG_INVALID_EVIDENCE")).isGreaterThanOrEqualTo(1);
    }

    @Test void eachReasonHasABoundedGauge() {
        for (String reason : ApprovalReviewReason.ALL) {
            double before = gauge(reason);
            PaymentApproval approval = held("OTHER".equals(reason) ? "UNEXPECTED_CODE" : reason);
            assertThat(gauge(reason)).isEqualTo(before + 1);
            assertThat(allListed(reason)).contains(approval.getApprovalId());
        }
    }

    @Test void eachNonHeldStatusUsesRealTransitionsAndIsExcludedAcrossAllPages() {
        PaymentApproval control = held("PG_INVALID_EVIDENCE");
        for (PaymentApprovalStatus status : PaymentApprovalStatus.values()) {
            if (status == PaymentApprovalStatus.REVIEW_REQUIRED) continue;
            Payment payment = payments.saveAndFlush(Payment.receive(request(), "PORTONE_TEST"));
            Instant at = Payment.now();
            PaymentApproval approval = PaymentApproval.receive(payment, new PaymentApprovalInput(id(), payment.getOrderNo(), id(), new ApprovalToken("secret")), "ciphertext", at);
            if (List.of(PaymentApprovalStatus.ADMITTED, PaymentApprovalStatus.FAILURE_CONFIRMED, PaymentApprovalStatus.SUCCEEDED).contains(status))
                approval.admit(new OrderAdmissionReceipt(approval.getApprovalId(), payment.getOrderNo(), payment.getPaymentId(), at.plusSeconds(1), approval.getExpiresAt()));
            if (status == PaymentApprovalStatus.FAILURE_CONFIRMED) {
                approval.prepare(at.plusSeconds(2)); approval.recordFailure(at.plusSeconds(3));
            }
            if (status == PaymentApprovalStatus.FAILED) approval.failBeforeAdmission(at.plusSeconds(2), at.plusSeconds(3));
            if (status == PaymentApprovalStatus.EXPIRED) approval.expire(at.plusSeconds(3));
            if (status == PaymentApprovalStatus.SUCCEEDED) {
                payment.confirm(new PgResult("PORTONE_TEST", payment.getPaymentId(), payment.getOrderNo(), payment.getAmount(), payment.getCurrency(), "pg-" + id(), at.plusSeconds(2), PgResult.Decision.APPROVED, null, null, null), id(), at.plusSeconds(3));
                payments.saveAndFlush(payment); approval.succeed(payment, at.plusSeconds(3));
            }
            approvals.saveAndFlush(approval);
            assertThat(approvals.findByApprovalId(approval.getApprovalId()).orElseThrow().getStatus()).isEqualTo(status);
            assertThat(http("GET", ROOT + "/" + approval.getApprovalId(), null, "ADMIN").status()).isEqualTo(404);
            assertThat(allListed()).contains(control.getApprovalId()).doesNotContain(approval.getApprovalId());
        }
    }

    @Test void historyRemainsVisibleAfterResolutionButNewNotesAreRefused() {
        PaymentApproval approval = held("PG_INVALID_EVIDENCE");
        String command = id();
        String path = ROOT + "/" + approval.getApprovalId() + "/investigations";
        assertThat(http("POST", path, Map.of("requestId", command, "note", "first finding"), "ADMIN").status()).isEqualTo(200);
        assertThat(allListed()).contains(approval.getApprovalId());
        resolveFailed(approval);
        Response detail = http("GET", ROOT + "/" + approval.getApprovalId(), null, "ADMIN");
        assertThat(detail.status()).isEqualTo(200);
        assertThat(detail.body().path("data").path("status").asText()).isEqualTo("FAILED");
        Response history = http("GET", path, null, "ADMIN");
        assertThat(history.status()).isEqualTo(200);
        assertThat(history.body().path("data").path("totalElements").asInt()).isEqualTo(1);
        assertThat(history.body().toString()).contains("first finding");
        assertThat(http("POST", path, Map.of("requestId", command, "note", "first finding"), "ADMIN").status()).isEqualTo(409);
        assertThat(http("POST", ROOT + "/" + approval.getApprovalId() + "/investigations", Map.of("requestId", id(), "note", "check"), "ADMIN").status()).isEqualTo(409);
        assertThat(allListed()).doesNotContain(approval.getApprovalId());
    }

    @Test void investigationReadBeforeResolverCommitCannotUseStaleHeldState() throws Exception {
        PaymentApproval approval = held("PG_INVALID_EVIDENCE");
        Gate gate = POINTS.arm("PaymentApprovalRepository.findPaymentIdByApprovalId", "investigation");
        Run<ApprovalInvestigationView> investigation = PausePoints.start("investigation", () ->
                record.execute(new ApprovalInvestigationRequest(id(), approval.getApprovalId(), "usr_operator", "check")));
        gate.awaitArrival();
        resolveFailed(approval);
        gate.release();
        assertThat(investigation.failure()).isInstanceOfSatisfying(BusinessException.class,
                failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.CONFLICT));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM approval_investigation WHERE approval_id=?", Integer.class, approval.getApprovalId())).isZero();
        assertThat(POINTS.reset()).isEmpty();
    }

    @Test void inspectionFailuresAreRecordedWithoutReplacingTheValidObservation() {
        for (RuntimeException failure : List.of(new IllegalArgumentException("invalid"), new IllegalStateException("down"))) {
            PaymentApproval approval = held("PG_INVALID_EVIDENCE");
            Instant at = approval.getReceivedAt().plusSeconds(2);
            PgPaymentApprovalGateway pg = mock(PgPaymentApprovalGateway.class);
            when(pg.inspect(approval.purchase())).thenThrow(failure);
            // Invoke inspect directly to verify its exact original exception, before execute classifies it.
            ResolvePaymentApproval resolver = resolver(pg, at);
            String lease = id();
            new TransactionTemplate(transactions).executeWithoutResult(tx -> approvals.lockByApprovalId(approval.getApprovalId()).orElseThrow().claim(lease, at, Duration.ofSeconds(60)));
            new TransactionTemplate(transactions).executeWithoutResult(tx -> approvals.lockByApprovalId(approval.getApprovalId()).orElseThrow().recordObservation(new ApprovalObservation(ApprovalObservation.State.READY, null, null), at));
            assertThatThrownBy(() -> invokeInspect(resolver, approval, lease)).isSameAs(failure);
            assertThat(approvals.findByApprovalId(approval.getApprovalId()).orElseThrow().getLatestPgState()).isEqualTo(ApprovalObservation.State.READY);
            Response detail = http("GET", ROOT + "/" + approval.getApprovalId(), null, "ADMIN");
            assertThat(detail.body().path("data").path("lastInspectionError").asText()).isEqualTo(failure instanceof IllegalArgumentException ? "INVALID_EVIDENCE" : "UNAVAILABLE");
            assertThat(detail.body().path("data").path("lastInspectedAt").isNull()).isFalse();
            doReturn(new ApprovalObservation(ApprovalObservation.State.PENDING, null, null)).when(pg).inspect(approval.purchase());
            invokeInspect(resolver, approval, lease);
            assertThat(http("GET", ROOT + "/" + approval.getApprovalId(), null, "ADMIN").body().path("data").path("lastInspectionError").isNull()).isTrue();
        }
    }

    @Test void lostLeaseCannotSaveAnObservationOrAnInspectionError() {
        PaymentApproval approval = held("PG_INVALID_EVIDENCE");
        Instant at = approval.getReceivedAt().plusSeconds(2);
        PgPaymentApprovalGateway pg = mock(PgPaymentApprovalGateway.class);
        when(pg.inspect(approval.purchase())).thenReturn(new ApprovalObservation(ApprovalObservation.State.PENDING, null, null));
        String original = id();
        new TransactionTemplate(transactions).executeWithoutResult(tx -> approvals.lockByApprovalId(approval.getApprovalId()).orElseThrow().claim(original, at, Duration.ofSeconds(60)));
        when(pg.inspect(approval.purchase())).thenAnswer(call -> {
            new TransactionTemplate(transactions).executeWithoutResult(tx -> approvals.lockByApprovalId(approval.getApprovalId()).orElseThrow().claim(id(), at.plusSeconds(60), Duration.ofSeconds(60)));
            return new ApprovalObservation(ApprovalObservation.State.PENDING, null, null);
        });
        invokeInspect(resolver(pg, at.plusSeconds(61)), approval, original);
        IllegalStateException down = new IllegalStateException("down");
        doThrow(down).when(pg).inspect(approval.purchase());
        assertThatThrownBy(() -> invokeInspect(resolver(pg, at.plusSeconds(61)), approval, original)).isSameAs(down);
        Response detail = http("GET", ROOT + "/" + approval.getApprovalId(), null, "ADMIN");
        assertThat(detail.body().path("data").has("lastInspectedAt")).isTrue();
        assertThat(detail.body().path("data").path("lastInspectedAt").isNull()).isTrue();
        assertThat(approvals.findByApprovalId(approval.getApprovalId()).orElseThrow().getLatestPgState()).isNull();
    }

    private ApprovalObservation invokeInspect(ResolvePaymentApproval resolver, PaymentApproval approval, String token) {
        try {
            Method method = ResolvePaymentApproval.class.getDeclaredMethod("inspect", String.class, String.class, PaymentApproval.class);
            method.setAccessible(true);
            return (ApprovalObservation) method.invoke(resolver, approval.getApprovalId(), token, approval);
        } catch (InvocationTargetException failure) {
            throw (RuntimeException) failure.getCause();
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
    }
    private ResolvePaymentApproval resolver(PgPaymentApprovalGateway pg, Instant at) {
        return new ResolvePaymentApproval(payments, approvals, mock(OrderPaymentAdmissions.class), pg, mock(ApprovalPayloadProtection.class), mock(ResolvePayment.class), Clock.fixed(at, ZoneOffset.UTC), Duration.ofSeconds(60), Duration.ofSeconds(5), 10, transactions, lifecycle);
    }
    private void resolveFailed(PaymentApproval approval) {
        PgPaymentApprovalGateway pg = mock(PgPaymentApprovalGateway.class);
        Instant at = approval.getReceivedAt().plusSeconds(3);
        when(pg.inspect(approval.purchase())).thenReturn(new ApprovalObservation(ApprovalObservation.State.FAILED, null, at));
        resolver(pg, at).execute(approval.getApprovalId());
    }
    private String allListed() { return allListed(null); }
    private String allListed(String reason) {
        StringBuilder result = new StringBuilder();
        int page = 0;
        Response response;
        do {
            response = http("GET", ROOT + "?page=" + page++ + "&size=100" + (reason == null ? "" : "&reason=" + reason), null, "ADMIN");
            assertThat(response.status()).isEqualTo(200);
            result.append(response.body().path("data").path("content"));
        } while (page < response.body().path("data").path("totalPages").asInt());
        return result.toString();
    }

    @Test void rolesAndInvalidRequestsAreRejected() {
        PaymentApproval held = held("PG_REQUEST_REJECTED");
        for (String path : List.of(ROOT, ROOT + "/" + held.getApprovalId(), ROOT + "/" + held.getApprovalId() + "/investigations")) {
            assertThat(http("GET", path, null, null).status()).isEqualTo(401);
            for (String role : List.of("MEMBER", "CREATOR", "REVIEWER")) assertThat(http("GET", path, null, role).status()).isEqualTo(403);
        }
        String postPath = ROOT + "/" + held.getApprovalId() + "/investigations";
        assertThat(http("POST", postPath, Map.of("requestId", id(), "note", "check"), null).status()).isEqualTo(401);
        assertThat(http("POST", postPath, Map.of("requestId", id(), "note", "check"), "MEMBER").status()).isEqualTo(403);
        assertThat(http("GET", ROOT + "/" + id(), null, "ADMIN").status()).isEqualTo(404);
        for (String query : List.of("page=-1", "size=101", "size=0", "reason=invalid"))
            assertThat(http("GET", ROOT + "?" + query, null, "ADMIN").status()).isEqualTo(400);
        Payment payment = payments.saveAndFlush(Payment.receive(request(), "PORTONE_TEST"));
        PaymentApproval active = PaymentApproval.receive(payment, new PaymentApprovalInput(id(), payment.getOrderNo(), id(), new ApprovalToken("secret")), "ciphertext", Payment.now());
        approvals.saveAndFlush(active);
        assertThat(http("GET", ROOT + "/" + active.getApprovalId(), null, "ADMIN").status()).isEqualTo(404);
    }

    @Test void inspectPersistsLatestObservationWithinTheLockedTransaction() {
        PaymentApproval held = held("PG_INVALID_EVIDENCE");
        Instant at = held.getReceivedAt().plusSeconds(2);
        PgPaymentApprovalGateway pg = mock(PgPaymentApprovalGateway.class);
        when(pg.inspect(held.purchase())).thenReturn(new ApprovalObservation(ApprovalObservation.State.PENDING, null, null));
        ResolvePaymentApproval resolver = new ResolvePaymentApproval(payments, approvals, mock(OrderPaymentAdmissions.class), pg,
                mock(ApprovalPayloadProtection.class), mock(ResolvePayment.class), Clock.fixed(at, ZoneOffset.UTC),
                Duration.ofSeconds(60), Duration.ofSeconds(5), 10, transactions, lifecycle);
        resolver.execute(held.getApprovalId());
        PaymentApproval stored = approvals.findByApprovalId(held.getApprovalId()).orElseThrow();
        assertThat(stored.getLatestPgState()).isEqualTo(ApprovalObservation.State.PENDING);
        assertThat(stored.getLatestPgObservedAt()).isEqualTo(at);
        assertThat(stored.getReviewStartedAt()).isEqualTo(held.getReviewStartedAt());
    }

    @Test void scrapeUsesOneGroupedQueryAndConstraintsRejectImpossibleObservations() {
        held("PG_INVALID_EVIDENCE");
        clearInvocations(approvals);
        prometheus.scrape();
        verify(approvals, times(1)).countReviewsByReason();
        PaymentApproval approval = held("PG_INVALID_EVIDENCE");
        assertThatThrownBy(() -> jdbc.update("UPDATE payment_approval SET latest_pg_state='PARTIAL_CANCELLED', latest_pg_observed_at=received_at WHERE approval_id=?", approval.getApprovalId())).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE payment_approval SET latest_pg_state='PENDING' WHERE approval_id=?", approval.getApprovalId())).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE payment_approval SET review_started_at=received_at - INTERVAL 1 SECOND WHERE approval_id=?", approval.getApprovalId())).isInstanceOf(DataAccessException.class);
        // V36: a verified full cancellation is a valid observation (decision 115).
        assertThat(jdbc.update("UPDATE payment_approval SET latest_pg_state='CANCELLED', latest_pg_observed_at=received_at WHERE approval_id=?", approval.getApprovalId())).isEqualTo(1);
    }
    private double gauge(String reason) {
        String line = prometheus.scrape().lines().filter(l -> l.startsWith("stove_payment_approval_review_required{") && l.contains("reason=\"" + reason + "\"")).findFirst().orElseThrow();
        return Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1));
    }
    private PaymentApproval held(String reason) {
        Payment payment = payments.saveAndFlush(Payment.receive(request(), "PORTONE_TEST"));
        Instant at = Payment.now();
        PaymentApproval held = PaymentApproval.receive(payment, new PaymentApprovalInput(id(), payment.getOrderNo(), id(), new ApprovalToken("secret")), "ciphertext", at);
        held.stopPosts(reason, at);
        return approvals.saveAndFlush(held);
    }
    private Response http(String method, String path, Object body, String role) {
        RestClient.RequestBodySpec request = RestClient.create("http://127.0.0.1:" + port).method(HttpMethod.valueOf(method)).uri(path);
        if (role != null) request.header("Authorization", "Bearer " + token("usr_operator", role));
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).body(body);
        return request.exchange((sent, reply) -> new Response(reply.getStatusCode().value(), reply.getHeaders().getFirst("Cache-Control"), mapper.readTree(reply.getBody())));
    }
    private record Response(int status, String cache, JsonNode body) { }
}
