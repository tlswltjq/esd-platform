package com.stove.order;

import static com.stove.order.OrderFixtures.id;
import static org.assertj.core.api.Assertions.assertThat;
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
import com.stove.order.core.domain.LicenseRestorationConfirmation;
import com.stove.order.core.domain.LicenseSuspensionConfirmation;
import com.stove.order.core.domain.Order;
import com.stove.order.core.domain.OrderRepository;
import com.stove.order.core.domain.OrderRequest;
import com.stove.order.core.domain.OrderStatus;
import com.stove.order.core.domain.PaymentResult;
import com.stove.order.core.domain.PublishedProduct;
import com.stove.order.core.domain.Refund;
import com.stove.order.core.domain.RefundAttempt;
import com.stove.order.core.domain.RefundAttemptRepository;
import com.stove.order.core.domain.RefundFailureConfirmation;
import com.stove.order.core.domain.RefundPaymentConfirmation;
import com.stove.order.core.domain.RefundRepository;
import com.stove.order.core.domain.RefundReviewDecision;
import com.stove.order.core.domain.RefundReviewRequest;
import com.stove.order.core.domain.RefundStatus;
import com.stove.order.core.port.CatalogProducts;
import com.stove.order.core.service.ApplyLicenseGrant;
import com.stove.order.core.service.ApplyRefundPaymentConfirmation;
import com.stove.order.core.service.ApplyRefundRestoration;
import com.stove.order.core.service.ApplyRefundSuspension;
import com.stove.order.core.service.DecideRefund;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
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

/** Real HTTP/JWT/MySQL boundary; Payment failure and License completion are contract fixtures. */
class RefundRetryApiIntegrationTest extends OrderIntegrationTestBase {
    private static final Instant BASE = Instant.now().truncatedTo(ChronoUnit.MICROS);
    private static final AtomicLong PRODUCTS = new AtomicLong(1000);
    @Autowired OrderRepository orders;
    @Autowired RefundRepository refunds;
    @Autowired RefundAttemptRepository attempts;
    @Autowired ApplyLicenseGrant applyGrant;
    @Autowired ApplyRefundSuspension suspend;
    @Autowired ApplyRefundRestoration restore;
    @Autowired ApplyRefundPaymentConfirmation confirmPayment;
    @Autowired DecideRefund decide;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean CatalogProducts catalog;
    @MockitoBean Clock clock;
    @MockitoSpyBean OutboxRecorder outbox;
    @LocalServerPort int port;

    @BeforeEach @AfterEach void clear() {
        reset(outbox); when(clock.instant()).thenReturn(BASE);
        jdbc.update("DELETE FROM order_refund_attempt ORDER BY id DESC"); jdbc.update("DELETE FROM order_license_grant");
        jdbc.update("DELETE FROM order_refund"); jdbc.update("DELETE FROM purchase_order");
        jdbc.update("DELETE FROM outbox_event"); jdbc.update("DELETE FROM processed_event");
    }

    @Test void restoredFailureCanBeDiscoveredAndRetriedWithoutMovingTheFirstEligibilityOrPurchaseFacts() throws Exception {
        Fixture fixture = failed(true);
        PaymentResult purchase = orders.findById(fixture.order().getId()).orElseThrow().paymentResult();
        JsonNode read = read(fixture.order()).data();
        assertThat(read.path("status").asText()).isEqualTo("FAILED");
        assertThat(read.path("retryableAttemptId").asText()).isEqualTo(fixture.previous().getAttemptId());
        assertThat(read.path("receivedAt").asText()).isEqualTo(BASE.toString());
        Response first = retry(fixture); Response duplicate = retry(fixture);
        assertReceipt(first, fixture); assertThat(duplicate.data()).isEqualTo(first.data());
        assertThat(duplicate.status()).isEqualTo(202);
        Refund current = refund(fixture.order());
        assertThat(current.getStatus()).isEqualTo(RefundStatus.PENDING_LICENSE_SUSPENSION);
        assertThat(current.getReceivedAt()).isEqualTo(BASE); assertThat(current.isAutomaticRefundEligible()).isTrue();
        assertThat(current.getRefundId()).isEqualTo(fixture.previous().getRefundId());
        assertThat(read(fixture.order()).data().path("retryableAttemptId").isNull()).isTrue();
        assertThat(attempts.count()).isEqualTo(1); assertThat(events("LicenseSuspensionRequested")).isEqualTo(2);
        assertThat(events("RefundExecutionRequested")).isEqualTo(1);
        JsonNode payload = mapper.readTree(jdbc.queryForObject("SELECT payload FROM outbox_event WHERE event_id=?", String.class,
                current.getSuspensionRequestEventId()));
        assertThat(payload.path("refundId").asText()).isEqualTo(current.getRefundId());
        assertThat(payload.path("orderNo").asText()).isEqualTo(fixture.order().getOrderNo());
        assertThat(payload.path("buyerSubject").asText()).isEqualTo(fixture.order().getBuyerSubject());
        assertThat(payload.path("paymentId").asText()).isEqualTo(fixture.order().getPaymentId());
        assertThat(payload.path("productId").asLong()).isEqualTo(fixture.order().getProductId());
        assertThat(payload.path("productCode").asText()).isEqualTo(fixture.order().getProductCode());
        assertThat(jdbc.queryForObject("SELECT partition_key FROM outbox_event WHERE event_id=?", String.class,
                current.getSuspensionRequestEventId())).isEqualTo(fixture.order().getOrderNo());
        assertThat(orders.findById(fixture.order().getId()).orElseThrow().paymentResult()).isEqualTo(purchase);
    }

    @Test void concurrentHttpRetriesCommitOneStableReceiptAndOneAdditionalSuspension() throws Exception {
        Fixture fixture = failed(true); String bearer = token("usr_buyer", "MEMBER");
        CountDownLatch start = new CountDownLatch(1); ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Response> first = pool.submit(() -> { start.await(); return retry(fixture.order().getOrderNo(), fixture.previous().getAttemptId(), bearer); });
            Future<Response> second = pool.submit(() -> { start.await(); return retry(fixture.order().getOrderNo(), fixture.previous().getAttemptId(), bearer); });
            start.countDown(); Response one = first.get(15, TimeUnit.SECONDS); Response two = second.get(15, TimeUnit.SECONDS);
            assertReceipt(one, fixture); assertReceipt(two, fixture); assertThat(one.data()).isEqualTo(two.data());
            assertThat(events("LicenseSuspensionRequested")).isEqualTo(2); assertThat(attempts.count()).isEqualTo(1);
        } finally { start.countDown(); pool.shutdownNow(); }
    }

    @Test void aLateDuplicateCannotStartAnotherRetryAfterTheSuccessorAlsoFailsAndIsRestored() {
        Fixture fixture = failed(true); Response original = retry(fixture);
        when(clock.instant()).thenReturn(BASE.plusSeconds(86402));
        Refund current = refund(fixture.order());
        suspend.execute(new LicenseSuspensionConfirmation(id(), BASE.plusSeconds(86401), current.getSuspensionRequestEventId(),
                current.getRefundId(), fixture.previous().getLicenseId(), fixture.order().getOrderNo(), "usr_buyer",
                fixture.order().getProductId(), fixture.order().getProductCode(), fixture.order().getPaymentId()));
        RefundAttempt successor = currentAttempt(fixture.order());
        assertThat(successor.getAttemptNo()).isEqualTo(2);
        assertThat(successor.getPreviousAttemptId()).isEqualTo(fixture.previous().getAttemptId());
        assertThat(successor.getAttemptId()).isNotEqualTo(fixture.previous().getAttemptId());
        assertThat(retry(fixture).data()).isEqualTo(original.data());
        failAndRestore(fixture.order(), successor, BASE.plusSeconds(86403), true);
        assertThat(read(fixture.order()).data().path("retryableAttemptId").asText()).isEqualTo(successor.getAttemptId());
        Map<String, List<Map<String, Object>>> before = snapshot();
        assertThat(retry(fixture).data()).isEqualTo(original.data()); assertThat(snapshot()).isEqualTo(before);
        Response next = retry(fixture.order().getOrderNo(), successor.getAttemptId(), token("usr_buyer", "MEMBER"));
        assertThat(next.status()).isEqualTo(202); assertThat(next.data().path("previousAttemptId").asText()).isEqualTo(successor.getAttemptId());
        assertThat(events("LicenseSuspensionRequested")).isEqualTo(3); assertThat(events("RefundExecutionRequested")).isEqualTo(2);
        assertThat(attempts.count()).isEqualTo(2); assertThat(refund(fixture.order()).getReceivedAt()).isEqualTo(BASE);
    }

    @Test void outboxFlushFailureRollsBackTheReceiptAndTheSameHttpRequestCanRecover() {
        Fixture fixture = failed(true); Map<String, List<Map<String, Object>>> before = snapshot();
        new TransactionTemplate(transactions).executeWithoutResult(tx -> doAnswer(call -> {
            call.callRealMethod(); orders.flush(); throw new IllegalStateException("after retry receipt and outbox flush");
        }).when(outbox).record(anyString(), anyString(), any(DomainEvent.class)));
        try {
            assertThat(retry(fixture).status()).isEqualTo(500); assertThat(snapshot()).isEqualTo(before);
        } finally { reset(outbox); }
        assertReceipt(retry(fixture), fixture); assertThat(events("LicenseSuspensionRequested")).isEqualTo(2);
    }

    @Test void confirmedFailureAloneCannotRetryBeforeActualLicenseRestoration() {
        Fixture fixture = failed(false); Map<String, List<Map<String, Object>>> before = snapshot();
        assertThat(read(fixture.order()).data().path("status").asText()).isEqualTo("PROCESSING");
        assertThat(read(fixture.order()).data().path("retryableAttemptId").isNull()).isTrue();
        assertThat(retry(fixture).status()).isEqualTo(409); assertThat(snapshot()).isEqualTo(before);
    }

    @Test void anotherBuyerMissingOrderMissingRefundAndWrongAttemptCannotChangeAnything() {
        Fixture fixture = failed(true); Order noRefund = paid(); Fixture other = failed(true);
        Map<String, List<Map<String, Object>>> before = snapshot();
        assertThat(retry(fixture.order().getOrderNo(), fixture.previous().getAttemptId(), token("another", "MEMBER")).status()).isEqualTo(404);
        assertThat(retry(id(), fixture.previous().getAttemptId(), token("usr_buyer", "MEMBER")).status()).isEqualTo(404);
        assertThat(retry(noRefund.getOrderNo(), fixture.previous().getAttemptId(), token("usr_buyer", "MEMBER")).status()).isEqualTo(404);
        assertThat(retry(fixture.order().getOrderNo(), id(), token("usr_buyer", "MEMBER")).status()).isEqualTo(409);
        assertThat(retry(fixture.order().getOrderNo(), other.previous().getAttemptId(), token("usr_buyer", "MEMBER")).status()).isEqualTo(409);
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test void validMemberJwtIsRequiredAndForgedBuyerFieldsCannotAuthorizeTheCaller() {
        Fixture fixture = failed(true); Map<String, List<Map<String, Object>>> before = snapshot();
        String path = path(fixture.order().getOrderNo()) + "/retries";
        Map<String, String> body = Map.of("previousAttemptId", fixture.previous().getAttemptId(), "buyerSubject", "usr_buyer");
        assertThat(http("POST", path, body, null).status()).isEqualTo(401);
        for (String role : new String[]{"ADMIN", "CREATOR", "REVIEWER"})
            assertThat(http("POST", path, body, token("usr_buyer", role)).status()).isEqualTo(403);
        assertThat(http("POST", path, body, token("usr_buyer", "MEMBER", ISSUER, "wrong-audience", Instant.now().plusSeconds(60))).status()).isEqualTo(401);
        assertThat(http("POST", path, body, token("usr_buyer", "MEMBER", ISSUER, "esd-api", Instant.now().minusSeconds(120))).status()).isEqualTo(401);
        assertThat(http("POST", path, body, token("another", "MEMBER")).status()).isEqualTo(404);
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test void originalRefundReceptionStillReturnsTheFailedResultWithoutCreatingANewAttempt() {
        Fixture fixture = failed(true); Map<String, List<Map<String, Object>>> before = snapshot();
        Response response = http("POST", path(fixture.order().getOrderNo()), null, token("usr_buyer", "MEMBER"));
        assertThat(response.status()).isEqualTo(200); assertThat(response.data().path("status").asText()).isEqualTo("FAILED");
        assertThat(response.data().path("retryableAttemptId").asText()).isEqualTo(fixture.previous().getAttemptId());
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test void uncertainPendingAndCompletedAttemptsAreNotNewRetryTargets() {
        Fixture fixture = failed(true); retry(fixture);
        when(clock.instant()).thenReturn(BASE.plusSeconds(86402));
        Refund current = refund(fixture.order());
        suspend.execute(new LicenseSuspensionConfirmation(id(), BASE.plusSeconds(86401), current.getSuspensionRequestEventId(),
                current.getRefundId(), fixture.previous().getLicenseId(), fixture.order().getOrderNo(), "usr_buyer",
                fixture.order().getProductId(), fixture.order().getProductCode(), fixture.order().getPaymentId()));
        RefundAttempt pending = currentAttempt(fixture.order());
        Map<String, List<Map<String, Object>>> before = snapshot();
        assertThat(retry(fixture.order().getOrderNo(), pending.getAttemptId(), token("usr_buyer", "MEMBER")).status()).isEqualTo(409);
        assertThat(snapshot()).isEqualTo(before);
        confirmPayment.execute(new RefundPaymentConfirmation(id(), BASE.plusSeconds(86403), current.getRefundId(), pending.getAttemptId(),
                pending.getExecutionRequestEventId(), current.getOrderNo(), fixture.order().getPaymentId(), "usr_buyer", fixture.order().getAmount(),
                "KRW", "PG_QUERY_UNATTRIBUTED", "pg-original", List.of("cancel-complete")));
        before = snapshot();
        assertThat(retry(fixture.order().getOrderNo(), pending.getAttemptId(), token("usr_buyer", "MEMBER")).status()).isEqualTo(409);
        assertThat(snapshot()).isEqualTo(before); assertThat(read(fixture.order()).data().path("retryableAttemptId").isNull()).isTrue();
    }

    @Test void aRejectedReviewCannotBeReopenedByNamingAnotherFailedAttempt() {
        Fixture fixture = failed(true); Order rejected = paid();
        when(clock.instant()).thenReturn(BASE.plusSeconds(14401));
        assertThat(http("POST", path(rejected.getOrderNo()), null, token("usr_buyer", "MEMBER")).status()).isEqualTo(202);
        Refund refund = refund(rejected);
        decide.execute(new RefundReviewRequest(id(), refund.getRefundId(), "usr_admin", RefundReviewDecision.REJECTED, "심사 반려"));
        Map<String, List<Map<String, Object>>> before = snapshot();
        assertThat(read(rejected).data().path("status").asText()).isEqualTo("REJECTED");
        assertThat(read(rejected).data().path("retryableAttemptId").isNull()).isTrue();
        assertThat(retry(rejected.getOrderNo(), fixture.previous().getAttemptId(), token("usr_buyer", "MEMBER")).status()).isEqualTo(409);
        assertThat(snapshot()).isEqualTo(before);
    }

    private Fixture failed(boolean restored) {
        Order paid = paid(); String licenseId = id();
        applyGrant.execute(new LicenseGrant(id(), BASE.minusSeconds(30), licenseId, paid.getOrderNo(), "usr_buyer",
                paid.getProductId(), paid.getProductCode(), paid.getPaymentId()));
        when(clock.instant()).thenReturn(BASE);
        assertThat(http("POST", path(paid.getOrderNo()), null, token("usr_buyer", "MEMBER")).status()).isEqualTo(202);
        Refund refund = refund(paid); when(clock.instant()).thenReturn(BASE.plusSeconds(2));
        suspend.execute(new LicenseSuspensionConfirmation(id(), BASE.plusSeconds(1), refund.getSuspensionRequestEventId(), refund.getRefundId(),
                licenseId, paid.getOrderNo(), "usr_buyer", paid.getProductId(), paid.getProductCode(), paid.getPaymentId()));
        RefundAttempt previous = currentAttempt(paid);
        failAndRestore(paid, previous, BASE.plusSeconds(3), restored);
        when(clock.instant()).thenReturn(BASE.plusSeconds(86400));
        return new Fixture(paid, previous);
    }

    private void failAndRestore(Order order, RefundAttempt attempt, Instant failedAt, boolean restored) {
        when(clock.instant()).thenReturn(failedAt.plusSeconds(1));
        restore.execute(new RefundFailureConfirmation(id(), failedAt, attempt.getRefundId(), attempt.getAttemptId(), attempt.getExecutionRequestEventId(),
                order.getOrderNo(), order.getPaymentId(), "usr_buyer", order.getAmount(), "KRW", "pg-original", "cancel-" + attempt.getAttemptNo(), "a".repeat(64)));
        if (restored) {
            RefundAttempt failed = currentAttempt(order);
            restore.execute(new LicenseRestorationConfirmation(id(), failedAt.plusSeconds(2), failed.getRestorationRequestEventId(), failed.getRefundId(),
                    failed.getAttemptId(), failed.getFailureEventId(), failed.getLicenseId(), order.getOrderNo(), "usr_buyer", order.getProductId(),
                    order.getProductCode(), order.getPaymentId(), failed.getSuspensionRequestEventId(), failed.getSuspensionEventId()));
            when(clock.instant()).thenReturn(failedAt.plusSeconds(3));
        }
    }

    private Order paid() {
        String code = "Game-" + id();
        Order order = Order.create("usr_buyer", new OrderRequest(id(), code, 3, 10000),
                new PublishedProduct(PRODUCTS.incrementAndGet(), code, "재시도 검증 상품", 3, 10000, "KRW"), id());
        order.apply(new PaymentResult(id(), BASE.minusSeconds(3600), order.getOrderNo(), order.getRequestEventId(), id(), "usr_buyer",
                order.getAmount(), "KRW", OrderStatus.PAID, null, null));
        return orders.saveAndFlush(order);
    }
    private Refund refund(Order order) { return refunds.findByOrderNo(order.getOrderNo()).orElseThrow(); }
    private RefundAttempt currentAttempt(Order order) { return attempts.findByAttemptId(refund(order).getCurrentAttemptId()).orElseThrow(); }
    private int events(String type) { return jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE event_type=?", Integer.class, type); }
    private Map<String, List<Map<String, Object>>> snapshot() {
        Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
        for (String table : List.of("purchase_order", "order_refund", "order_refund_attempt", "order_license_grant", "processed_event", "outbox_event"))
            result.put(table, jdbc.queryForList("SELECT * FROM " + table + " ORDER BY id"));
        return result;
    }
    private void assertReceipt(Response response, Fixture fixture) {
        assertThat(response.status()).isEqualTo(202); assertThat(response.location()).isEqualTo(path(fixture.order().getOrderNo()));
        assertThat(response.cacheControl()).isEqualTo("no-store");
        assertThat(response.data().size()).isEqualTo(3);
        assertThat(response.data().path("refundId").asText()).isEqualTo(fixture.previous().getRefundId());
        assertThat(response.data().path("previousAttemptId").asText()).isEqualTo(fixture.previous().getAttemptId());
        assertThat(response.data().path("receivedAt").asText()).isEqualTo(BASE.plusSeconds(86400).toString());
    }
    private String path(String orderNo) { return "/api/v1/orders/" + orderNo + "/refund"; }
    private Response read(Order order) { return http("GET", path(order.getOrderNo()), null, token("usr_buyer", "MEMBER")); }
    private Response retry(Fixture fixture) { return retry(fixture.order().getOrderNo(), fixture.previous().getAttemptId(), token("usr_buyer", "MEMBER")); }
    private Response retry(String orderNo, String previous, String bearer) {
        return http("POST", path(orderNo) + "/retries", Map.of("previousAttemptId", previous), bearer);
    }
    private Response http(String method, String path, Object body, String bearer) {
        RestClient.RequestBodySpec request = RestClient.create("http://127.0.0.1:" + port).method(HttpMethod.valueOf(method)).uri(path);
        if (bearer != null) request.header("Authorization", "Bearer " + bearer);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).body(body);
        return request.exchange((sent, reply) -> new Response(reply.getStatusCode().value(), reply.getHeaders().getFirst("Location"),
                reply.getHeaders().getFirst("Cache-Control"), mapper.readTree(reply.getBody())));
    }
    private record Fixture(Order order, RefundAttempt previous) { }
    private record Response(int status, String location, String cacheControl, JsonNode body) { JsonNode data() { return body.path("data"); } }
}
