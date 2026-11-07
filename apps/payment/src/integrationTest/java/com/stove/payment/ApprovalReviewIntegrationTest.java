package com.stove.payment;

import static com.stove.payment.PaymentFixtures.id;
import static com.stove.payment.PaymentFixtures.request;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.payment.core.domain.ApprovalObservation;
import com.stove.payment.core.domain.ApprovalToken;
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
import io.micrometer.core.instrument.MeterRegistry;
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
import org.springframework.http.MediaType;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.client.RestClient;

class ApprovalReviewIntegrationTest extends PaymentIntegrationTestBase {
    private static final String ROOT = "/api/v1/admin/approval-reviews";
    @Autowired PaymentRepository payments;
    @Autowired PaymentApprovalRepository approvals;
    @Autowired PaymentOrderLifecycleRepository lifecycle;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ObjectMapper mapper;
    @Autowired MeterRegistry meters;
    @Autowired JdbcTemplate jdbc;
    @LocalServerPort int port;

    @Test void dbOnlyListDetailAndAppendUseJwtActorAndHideSecrets() {
        PaymentApproval held = held("PG_INVALID_EVIDENCE");
        Response list = http("GET", ROOT + "?reason=PG_INVALID_EVIDENCE", null, "ADMIN");
        assertThat(list.status()).isEqualTo(200);
        assertThat(list.cache()).isEqualTo("no-store");
        assertThat(list.body().toString()).contains(held.getApprovalId()).doesNotContain("protectedPayload", "token", "ciphertext");
        assertThat(http("GET", ROOT + "/" + held.getApprovalId(), null, "ADMIN").status()).isEqualTo(200);
        String path = ROOT + "/" + held.getApprovalId() + "/investigations";
        for (int n = 0; n < 2; n++) {
            Response note = http("POST", path, Map.of("requestId", id(), "note", "checked", "operatorSubject", "forged"), "ADMIN");
            assertThat(note.status()).isEqualTo(200);
            assertThat(note.body().path("data").path("operatorSubject").asText()).isEqualTo("usr_operator");
        }
        assertThat(http("GET", path, null, "ADMIN").body().path("data").path("totalElements").asInt()).isEqualTo(2);
        assertThat(meters.get("stove.payment.approval.review.required").tag("reason", "PG_INVALID_EVIDENCE").gauge().value()).isGreaterThanOrEqualTo(1);
    }

    @Test void eachReasonHasABoundedGaugeAndEachNonHeldStatusIsExcluded() {
        for (String reason : ApprovalReviewReason.ALL) {
            double before = meters.get("stove.payment.approval.review.required").tag("reason", reason).gauge().value();
            PaymentApproval approval = held("OTHER".equals(reason) ? "UNEXPECTED_CODE" : reason);
            assertThat(meters.get("stove.payment.approval.review.required").tag("reason", reason).gauge().value()).isEqualTo(before + 1);
            assertThat(http("GET", ROOT + "?reason=" + reason, null, "ADMIN").body().toString()).contains(approval.getApprovalId());
        }
        for (PaymentApprovalStatus status : PaymentApprovalStatus.values()) {
            if (status == PaymentApprovalStatus.REVIEW_REQUIRED) continue;
            PaymentApproval approval = held("PG_INVALID_EVIDENCE");
            if (List.of(PaymentApprovalStatus.SUCCEEDED, PaymentApprovalStatus.FAILED, PaymentApprovalStatus.EXPIRED).contains(status))
                jdbc.update("UPDATE payment_approval SET status=?, resolved_at=received_at, next_at=NULL, protected_payload=NULL WHERE approval_id=?", status.name(), approval.getApprovalId());
            else jdbc.update("UPDATE payment_approval SET status=? WHERE approval_id=?", status.name(), approval.getApprovalId());
            assertThat(http("GET", ROOT + "/" + approval.getApprovalId(), null, "ADMIN").status()).isEqualTo(404);
            assertThat(http("GET", ROOT, null, "ADMIN").body().toString()).doesNotContain(approval.getApprovalId());
        }
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
