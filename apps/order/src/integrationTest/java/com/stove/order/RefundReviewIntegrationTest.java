package com.stove.order;

import static com.stove.order.OrderFixtures.id;
import static com.stove.order.OrderFixtures.order;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.kafka.contract.DomainEvent;
import com.stove.common.messaging.outbox.OutboxRecorder;
import com.stove.order.core.domain.LicenseGrant;
import com.stove.order.core.domain.LicenseSuspensionConfirmation;
import com.stove.order.core.domain.Order;
import com.stove.order.core.domain.OrderRepository;
import com.stove.order.core.domain.OrderStatus;
import com.stove.order.core.domain.PaymentResult;
import com.stove.order.core.domain.Refund;
import com.stove.order.core.domain.RefundAttempt;
import com.stove.order.core.domain.RefundAttemptRepository;
import com.stove.order.core.domain.RefundPaymentConfirmation;
import com.stove.order.core.domain.RefundRepository;
import com.stove.order.core.domain.RefundStatus;
import com.stove.order.core.port.CatalogProducts;
import com.stove.order.core.service.ApplyLicenseGrant;
import com.stove.order.core.service.ApplyRefundSuspension;
import com.stove.order.core.service.ApplyRefundPaymentConfirmation;
import com.stove.order.core.service.RequestRefund;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

class RefundReviewIntegrationTest extends OrderIntegrationTestBase {
    private static final Instant BASE = Instant.now().truncatedTo(ChronoUnit.MICROS);
    private static final String ADMIN = "/api/v1/admin/refunds";
    private final AtomicLong products = new AtomicLong(100);
    @Autowired OrderRepository orders;
    @Autowired RefundRepository refunds;
    @Autowired RequestRefund requestRefund;
    @Autowired ApplyLicenseGrant applyGrant;
    @Autowired ApplyRefundSuspension applySuspension;
    @Autowired ApplyRefundPaymentConfirmation applyPayment;
    @Autowired RefundAttemptRepository attempts;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean CatalogProducts catalog;
    @MockitoBean Clock clock;
    @MockitoSpyBean OutboxRecorder outbox;
    @LocalServerPort int port;

    @BeforeEach void clear() {
        reset(outbox); when(clock.instant()).thenReturn(BASE);
        jdbc.update("DELETE FROM order_refund_attempt"); jdbc.update("DELETE FROM order_license_grant");
        jdbc.update("DELETE FROM order_refund"); jdbc.update("DELETE FROM purchase_order");
        jdbc.update("DELETE FROM outbox_event"); jdbc.update("DELETE FROM processed_event");
    }

    @Test void approvalRecordsJwtActorAndOneSuspensionWithoutChangingThePurchase() {
        Order paid = saved(); grant(paid); Refund pending = pending(paid);
        JsonNode originalOrder = http("GET", "/api/v1/orders/" + paid.getOrderNo(), null, "usr_buyer", "MEMBER").data();
        Map<String, Object> body = Map.of("requestId", id(), "decision", "APPROVED", "reason", "구매 근거 확인", "operatorSubject", "forged");
        Response first = decision(pending, body, "admin-first");
        assertThat(first.status()).isEqualTo(200); assertThat(first.data().path("operatorSubject").asText()).isEqualTo("admin-first");
        assertThat(first.data().path("decidedAt").asText()).isEqualTo(BASE.toString()); assertThat(first.cache()).isEqualTo("no-store");
        Refund stored = current(pending); assertThat(stored.getStatus()).isEqualTo(RefundStatus.PENDING_LICENSE_SUSPENSION);
        assertThat(stored.getReceivedAt()).isEqualTo(pending.getReceivedAt()); assertThat(stored.isAutomaticRefundEligible()).isFalse();
        assertThat(count("outbox_event")).isEqualTo(1); assertThat(count("order_refund_attempt")).isZero();
        assertThat(jdbc.queryForObject("SELECT event_type FROM outbox_event", String.class)).isEqualTo("LicenseSuspensionRequested");
        assertThat(http("GET", "/api/v1/orders/" + paid.getOrderNo(), null, "usr_buyer", "MEMBER").data()).isEqualTo(originalOrder);
        assertThat(customer("GET", paid).data().path("status").asText()).isEqualTo("PROCESSING");
        assertThat(customer("GET", paid).data().path("rejectionReason").isNull()).isTrue();
    }

    @Test void approvalWaitsForRealGrantAndItsLaterDeliveryStartsSuspensionExactlyOnce() {
        Order paid = saved(); Refund pending = pending(paid); Map<String, Object> command = command("APPROVED");
        assertThat(decision(pending, command, "admin").status()).isEqualTo(200);
        assertThat(current(pending).getStatus()).isEqualTo(RefundStatus.PENDING_LICENSE_GRANT); assertThat(count("outbox_event")).isZero();
        LicenseGrant granted = grantInput(paid); applyGrant.execute(granted); applyGrant.execute(granted);
        assertThat(current(pending).getStatus()).isEqualTo(RefundStatus.PENDING_LICENSE_SUSPENSION);
        assertThat(count("order_license_grant")).isEqualTo(1); assertThat(count("outbox_event")).isEqualTo(1);
        assertThat(decision(pending, command, "admin").status()).isEqualTo(200); assertThat(count("outbox_event")).isEqualTo(1);
    }

    @Test void rejectionDoesNotSuspendAndIsVisibleToTheBuyerWithoutInternalAudit() {
        Order paid = saved(); Refund pending = pending(paid);
        Response rejected = decision(pending, command("REJECTED"), "admin"); assertThat(rejected.status()).isEqualTo(200);
        grant(paid); assertThat(current(pending).getStatus()).isEqualTo(RefundStatus.REJECTED);
        assertThat(count("outbox_event")).isZero(); assertThat(count("order_refund_attempt")).isZero();
        Response get = customer("GET", paid); Response repeated = customer("POST", paid);
        assertThat(get.status()).isEqualTo(200); assertThat(repeated.status()).isEqualTo(200); assertThat(repeated.data()).isEqualTo(get.data());
        assertThat(get.data().path("status").asText()).isEqualTo("REJECTED");
        assertThat(get.data().path("rejectionReason").asText()).isEqualTo("확인한 판정 사유");
        assertThat(get.data().path("completedAt").isNull()).isTrue(); assertThat(get.data().has("operatorSubject")).isFalse();
        assertThat(get.data().has("requestId")).isFalse(); assertThat(count("order_refund")).isEqualTo(1);
    }

    @Test void manualApprovalFlowsThroughConfirmedSuspensionAndPaymentWhileKeepingTheFirstReceipt() {
        Order paid = saved(); LicenseGrant granted = grantInput(paid); applyGrant.execute(granted);
        Refund pending = pending(paid); Map<String, Object> command = command("APPROVED");
        Response first = decision(pending, command, "admin"); Refund approved = current(pending);
        when(clock.instant()).thenReturn(BASE.plusSeconds(2));
        applySuspension.execute(new LicenseSuspensionConfirmation(id(), BASE.plusSeconds(1), approved.getSuspensionRequestEventId(),
                pending.getRefundId(), granted.licenseId(), paid.getOrderNo(), paid.getBuyerSubject(), paid.getProductId(), paid.getProductCode(), paid.getPaymentId()));
        Refund executing = current(pending); RefundAttempt attempt = attempts.findByAttemptId(executing.getCurrentAttemptId()).orElseThrow();
        assertThat(executing.getStatus()).isEqualTo(RefundStatus.PENDING_PAYMENT_REFUND);
        applyPayment.execute(new RefundPaymentConfirmation(id(), BASE.plusSeconds(3), pending.getRefundId(), attempt.getAttemptId(),
                attempt.getExecutionRequestEventId(), paid.getOrderNo(), paid.getPaymentId(), paid.getBuyerSubject(), paid.getAmount(),
                paid.getCurrency(), "PG_RESPONSE", "provider-transaction", List.of("full-cancel")));
        assertThat(customer("GET", paid).data().path("status").asText()).isEqualTo("COMPLETED");
        assertThat(decision(pending, command, "admin").data()).isEqualTo(first.data());
        assertThat(count("order_refund_attempt")).isEqualTo(1); assertThat(count("outbox_event")).isEqualTo(2);
        assertThat(orders.findById(paid.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.PAID);
    }

    @Test void mismatchedStoredGrantRollsBackApprovalBeforeSuspension() {
        Order paid = saved(); grant(paid); Refund pending = pending(paid);
        jdbc.update("UPDATE order_license_grant SET buyer_subject='another' WHERE order_no=?", paid.getOrderNo());
        assertThat(decision(pending, command("APPROVED"), "admin").status()).isEqualTo(409);
        assertThat(current(pending).reviewReceipt()).isNull(); assertThat(current(pending).getStatus()).isEqualTo(RefundStatus.PENDING_REVIEW);
        assertThat(count("outbox_event")).isZero();
    }

    @Test void exactReplayReturnsTheImmutableReceiptAfterWorkflowAdvances() {
        Order paid = saved(); Refund pending = pending(paid); Map<String, Object> command = command("APPROVED");
        Response first = decision(pending, command, "admin"); grant(paid);
        when(clock.instant()).thenReturn(BASE.plusSeconds(60));
        Response retry = decision(pending, command, "admin"); assertThat(retry.data()).isEqualTo(first.data());
        assertThat(current(pending).getStatus()).isEqualTo(RefundStatus.PENDING_LICENSE_SUSPENSION); assertThat(count("outbox_event")).isEqualTo(1);
    }

    @Test void changedActorReasonVerdictOrCommandCannotOverwriteTheFirstVerdict() {
        Refund pending = pending(saved()); Map<String, Object> command = command("REJECTED"); Response first = decision(pending, command, "admin");
        for (Map<String, Object> changed : List.of(command("REJECTED"), command("APPROVED"),
                Map.<String, Object>of("requestId", command.get("requestId"), "decision", "REJECTED", "reason", "changed"),
                Map.<String, Object>of("requestId", command.get("requestId"), "decision", "APPROVED", "reason", command.get("reason"))))
            assertThat(decision(pending, changed, "admin").status()).isEqualTo(409);
        assertThat(decision(pending, command, "another-admin").status()).isEqualTo(409);
        assertThat(decision(pending, command, "admin").data()).isEqualTo(first.data()); assertThat(count("outbox_event")).isZero();
    }

    @Test void aCommandIdentifierCannotBeReusedForAnotherRefund() {
        Refund first = pending(saved()); Refund second = pending(saved()); Map<String, Object> command = command("REJECTED");
        assertThat(decision(first, command, "admin").status()).isEqualTo(200);
        assertThat(decision(second, command, "admin").status()).isEqualTo(409);
        assertThat(current(second).getStatus()).isEqualTo(RefundStatus.PENDING_REVIEW); assertThat(current(second).reviewReceipt()).isNull();
    }

    @Test void onlyValidAdministratorJwtCanListReadAndDecide() {
        Refund pending = pending(saved());
        for (String role : new String[]{null, "MEMBER", "CREATOR"}) {
            int expected = role == null ? 401 : 403;
            assertThat(http("GET", ADMIN, null, "actor", role).status()).isEqualTo(expected);
            assertThat(http("GET", ADMIN + "/" + pending.getRefundId(), null, "actor", role).status()).isEqualTo(expected);
            assertThat(http("POST", decisionPath(pending), command("APPROVED"), "actor", role).status()).isEqualTo(expected);
        }
        for (String bearer : new String[]{token("admin", "ADMIN", ISSUER, "wrong", Instant.now().plusSeconds(60)),
                token("admin", "ADMIN", ISSUER, "esd-api", Instant.now().minusSeconds(120))}) {
            assertThat(send("GET", ADMIN, null, bearer).status()).isEqualTo(401);
            assertThat(send("POST", decisionPath(pending), command("APPROVED"), bearer).status()).isEqualTo(401);
        }
        assertThat(current(pending).reviewReceipt()).isNull(); assertThat(count("outbox_event")).isZero();
        assertThat(http("POST", ADMIN + "/anything", command("APPROVED"), "admin", "ADMIN").status()).isEqualTo(403);
    }

    @Test void listingUsesAStableOldestFirstQueueAndDetailIncludesFirstAudit() {
        Refund first = pending(saved()); Refund second = pending(saved());
        Response page = http("GET", ADMIN + "?page=0&size=1", null, "admin", "ADMIN");
        assertThat(page.status()).isEqualTo(200); assertThat(page.cache()).isEqualTo("no-store");
        assertThat(page.data().path("totalElements").asInt()).isEqualTo(2); assertThat(page.data().path("totalPages").asInt()).isEqualTo(2);
        assertThat(page.data().path("content").get(0).path("refundId").asText()).isEqualTo(first.getRefundId());
        assertThat(http("GET", ADMIN + "?page=1&size=1", null, "admin", "ADMIN").data().path("content").get(0).path("refundId").asText()).isEqualTo(second.getRefundId());
        Response verdict = decision(first, command("REJECTED"), "admin");
        Response detail = http("GET", ADMIN + "/" + first.getRefundId(), null, "admin", "ADMIN");
        assertThat(detail.data().path("review")).isEqualTo(verdict.data()); assertThat(detail.data().path("buyerSubject").asText()).isEqualTo("usr_buyer");
        assertThat(http("GET", ADMIN, null, "admin", "ADMIN").data().path("totalElements").asInt()).isEqualTo(1);
        assertThat(http("GET", ADMIN + "?status=REJECTED", null, "admin", "ADMIN").data().path("totalElements").asInt()).isEqualTo(1);
    }

    @Test void invalidDecisionOrPagingAndUnknownRefundCannotProduceAuditOrEvents() {
        Refund pending = pending(saved());
        for (Object body : List.of(Map.of(), Map.of("requestId", "bad", "decision", "APPROVED", "reason", "reason"),
                Map.of("requestId", id(), "decision", "UNKNOWN", "reason", "reason"),
                Map.of("requestId", id(), "decision", "REJECTED", "reason", " "),
                Map.of("requestId", id(), "decision", "APPROVED", "reason", "x".repeat(501))))
            assertThat(decision(pending, body, "admin").status()).isEqualTo(400);
        for (String query : new String[]{"?page=-1", "?size=0", "?size=101", "?status=UNKNOWN"})
            assertThat(http("GET", ADMIN + query, null, "admin", "ADMIN").status()).isEqualTo(400);
        assertThat(http("GET", ADMIN + "/" + id(), null, "admin", "ADMIN").status()).isEqualTo(404);
        assertThat(http("POST", ADMIN + "/" + id() + "/decision", command("APPROVED"), "admin", "ADMIN").status()).isEqualTo(404);
        assertThat(current(pending).reviewReceipt()).isNull(); assertThat(count("outbox_event")).isZero();
    }

    @Test void automaticProcessingCannotBeOverriddenByAnAdministrator() {
        Order paid = saved(); when(clock.instant()).thenReturn(BASE.minusSeconds(7200));
        requestRefund.execute(paid.getBuyerSubject(), paid.getOrderNo()); when(clock.instant()).thenReturn(BASE);
        Refund automatic = refunds.findByOrderNo(paid.getOrderNo()).orElseThrow();
        assertThat(decision(automatic, command("REJECTED"), "admin").status()).isEqualTo(409);
        assertThat(current(automatic).getStatus()).isEqualTo(RefundStatus.PENDING_LICENSE_GRANT);
    }

    @Test void simultaneousOppositeVerdictsHaveOneWinnerAndOneConflict() throws Exception {
        Order paid = saved(); grant(paid); Refund pending = pending(paid);
        List<Response> replies = race(() -> decision(pending, command("APPROVED"), "admin-one"),
                () -> decision(pending, command("REJECTED"), "admin-two"));
        assertThat(replies.stream().map(Response::status)).containsExactlyInAnyOrder(200, 409);
        Refund stored = current(pending); assertThat(stored.reviewReceipt()).isNotNull();
        assertThat(count("outbox_event")).isEqualTo(stored.getStatus() == RefundStatus.REJECTED ? 0 : 1);
    }

    @Test void simultaneousExactReplaysReturnTheSameReceiptAndOnlyOneEvent() throws Exception {
        Order paid = saved(); grant(paid); Refund pending = pending(paid); Map<String, Object> command = command("APPROVED");
        List<Response> replies = race(() -> decision(pending, command, "admin"), () -> decision(pending, command, "admin"));
        assertThat(replies.stream().map(Response::status)).containsOnly(200); assertThat(replies.get(0).data()).isEqualTo(replies.get(1).data());
        assertThat(count("outbox_event")).isEqualTo(1);
    }

    @Test void simultaneousReuseOfOneCommandAcrossOrdersRollsBackTheLoser() throws Exception {
        Refund first = pending(saved()); Refund second = pending(saved()); Map<String, Object> command = command("REJECTED");
        List<Response> replies = race(() -> decision(first, command, "admin"), () -> decision(second, command, "admin"));
        assertThat(replies.stream().map(Response::status)).containsExactlyInAnyOrder(200, 409);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_refund WHERE review_request_id IS NOT NULL", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_refund WHERE status='PENDING_REVIEW'", Integer.class)).isEqualTo(1);
    }

    @Test void concurrentGrantAndApprovalProduceOneSuspensionRequest() throws Exception {
        Order paid = saved(); Refund pending = pending(paid);
        List<Response> replies = race(() -> decision(pending, command("APPROVED"), "admin"), () -> {
            grant(paid); return new Response(200, null, mapper.createObjectNode());
        });
        assertThat(replies.stream().map(Response::status)).containsOnly(200);
        assertThat(current(pending).getStatus()).isEqualTo(RefundStatus.PENDING_LICENSE_SUSPENSION); assertThat(count("outbox_event")).isEqualTo(1);
    }

    @Test void actualOutboxInsertFailureRollsBackVerdictAndAllowsTheSameCommandToRecover() {
        Order paid = saved(); grant(paid); Refund pending = pending(paid); Map<String, Object> command = command("APPROVED");
        new TransactionTemplate(transactions).executeWithoutResult(tx -> doAnswer(call -> {
            call.callRealMethod(); throw new IllegalStateException("after actual suspension insert");
        }).when(outbox).record(anyString(), anyString(), any(DomainEvent.class)));
        assertThat(decision(pending, command, "admin").status()).isEqualTo(500);
        assertThat(current(pending).reviewReceipt()).isNull(); assertThat(current(pending).getStatus()).isEqualTo(RefundStatus.PENDING_REVIEW);
        assertThat(count("outbox_event")).isZero(); reset(outbox);
        assertThat(decision(pending, command, "admin").status()).isEqualTo(200); assertThat(count("outbox_event")).isEqualTo(1);
    }

    @Test void databaseRejectsPartialOrForgedVerdictAudit() {
        Refund pending = pending(saved());
        assertThatThrownBy(() -> jdbc.update("UPDATE order_refund SET review_request_id=? WHERE refund_id=?", id(), pending.getRefundId())).hasStackTraceContaining("chk_order_refund_review");
        assertThatThrownBy(() -> jdbc.update("UPDATE order_refund SET status='REJECTED' WHERE refund_id=?", pending.getRefundId())).hasStackTraceContaining("chk_order_refund_review");
        assertThat(current(pending).getStatus()).isEqualTo(RefundStatus.PENDING_REVIEW);
    }

    private Order saved() {
        Order paid = order(); ReflectionTestUtils.setField(paid, "createdAt", BASE.minusSeconds(21600));
        long product = products.incrementAndGet();
        ReflectionTestUtils.setField(paid, "productId", product);
        ReflectionTestUtils.setField(paid, "productCode", "review-product-" + product);
        paid.apply(new PaymentResult(id(), BASE.minusSeconds(18000), paid.getOrderNo(), paid.getRequestEventId(), id(),
                paid.getBuyerSubject(), paid.getAmount(), paid.getCurrency(), OrderStatus.PAID, null, null));
        return orders.saveAndFlush(paid);
    }
    private Refund pending(Order paid) { requestRefund.execute(paid.getBuyerSubject(), paid.getOrderNo()); return refunds.findByOrderNo(paid.getOrderNo()).orElseThrow(); }
    private LicenseGrant grantInput(Order paid) { return new LicenseGrant(id(), BASE.minusSeconds(30), id(), paid.getOrderNo(), paid.getBuyerSubject(), paid.getProductId(), paid.getProductCode(), paid.getPaymentId()); }
    private void grant(Order paid) { applyGrant.execute(grantInput(paid)); }
    private Refund current(Refund refund) { return refunds.findByRefundId(refund.getRefundId()).orElseThrow(); }
    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
    private Map<String, Object> command(String decision) { return Map.of("requestId", id(), "decision", decision, "reason", "확인한 판정 사유"); }
    private String decisionPath(Refund refund) { return ADMIN + "/" + refund.getRefundId() + "/decision"; }
    private Response decision(Refund refund, Object command, String actor) { return http("POST", decisionPath(refund), command, actor, "ADMIN"); }
    private Response customer(String method, Order paid) { return http(method, "/api/v1/orders/" + paid.getOrderNo() + "/refund", null, paid.getBuyerSubject(), "MEMBER"); }
    private Response http(String method, String path, Object body, String actor, String role) { return send(method, path, body, role == null ? null : token(actor, role)); }
    private Response send(String method, String path, Object body, String bearer) {
        RestClient.RequestBodySpec request = RestClient.create("http://127.0.0.1:" + port).method(HttpMethod.valueOf(method)).uri(path);
        if (bearer != null) request.header("Authorization", "Bearer " + bearer);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).body(body);
        return request.exchange((sent, reply) -> new Response(reply.getStatusCode().value(), reply.getHeaders().getCacheControl(), mapper.readTree(reply.getBody())));
    }
    private List<Response> race(Reply first, Reply second) throws Exception {
        CountDownLatch start = new CountDownLatch(1); ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Response> one = pool.submit(() -> { start.await(); return first.run(); });
            Future<Response> two = pool.submit(() -> { start.await(); return second.run(); });
            start.countDown(); return List.of(one.get(20, TimeUnit.SECONDS), two.get(20, TimeUnit.SECONDS));
        } finally { start.countDown(); pool.shutdownNow(); }
    }
    @FunctionalInterface private interface Reply { Response run(); }
    private record Response(int status, String cache, JsonNode body) { JsonNode data() { return body.path("data"); } }
}
