package com.stove.order;

import static com.stove.order.OrderFixtures.id;
import static com.stove.order.OrderFixtures.order;
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
import com.stove.order.core.domain.Order;
import com.stove.order.core.domain.OrderRepository;
import com.stove.order.core.domain.OrderStatus;
import com.stove.order.core.domain.PaymentResult;
import com.stove.order.core.domain.Refund;
import com.stove.order.core.domain.RefundRepository;
import com.stove.order.core.domain.RefundStatus;
import com.stove.order.core.port.CatalogProducts;
import com.stove.order.core.service.ApplyLicenseGrant;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
import org.springframework.web.client.RestClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class RefundApiIntegrationTest extends OrderIntegrationTestBase {
    private static final Instant BASE = Instant.now().truncatedTo(ChronoUnit.MICROS);
    @Autowired OrderRepository orders;
    @Autowired RefundRepository refunds;
    @Autowired ApplyLicenseGrant applyGrant;
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

    @Test void ownerReceivesOneAcceptedReceiptAndCanReadItWithoutLeakingExecutionDetails() {
        Order paid = saved(true); grant(paid);
        Response first = http("POST", paid.getOrderNo(), null, token("usr_buyer", "MEMBER"));
        assertThat(first.status()).isEqualTo(202); assertThat(first.location()).isEqualTo(path(paid.getOrderNo()));
        assertThat(first.data().path("status").asText()).isEqualTo("PROCESSING");
        assertThat(first.data().path("amount").asLong()).isEqualTo(paid.getAmount());
        assertThat(first.data().has("attemptId")).isFalse(); assertThat(first.data().has("buyerSubject")).isFalse();
        Response second = http("POST", paid.getOrderNo(), null, token("usr_buyer", "MEMBER"));
        Response read = http("GET", paid.getOrderNo(), null, token("usr_buyer", "MEMBER"));
        assertThat(second.data()).isEqualTo(first.data()); assertThat(read.data()).isEqualTo(first.data());
        assertThat(count("order_refund")).isEqualTo(1); assertThat(count("outbox_event")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT event_type FROM outbox_event", String.class)).isEqualTo("LicenseSuspensionRequested");
        assertThat(orders.findById(paid.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.PAID);
    }

    @Test void requestBodyCannotForgeTheBuyerAmountCurrencyOrFinalStatus() {
        Order paid = saved(true);
        Response response = http("POST", paid.getOrderNo(), Map.of("buyerSubject", "another", "amount", 1, "currency", "USD", "status", "COMPLETED"), token("usr_buyer", "MEMBER"));
        assertThat(response.status()).isEqualTo(202); assertThat(response.data().path("amount").asLong()).isEqualTo(paid.getAmount());
        assertThat(response.data().path("currency").asText()).isEqualTo("KRW");
        assertThat(response.data().path("status").asText()).isEqualTo("PROCESSING");
        assertThat(refunds.findByOrderNo(paid.getOrderNo()).orElseThrow().getStatus()).isEqualTo(RefundStatus.PENDING_LICENSE_GRANT);
        assertThat(count("outbox_event")).isZero();
    }

    @Test void missingRefundMissingOrderAndAnotherBuyersOrderHaveTheSameNotFoundResponse() {
        Order paid = saved(true);
        assertThat(http("GET", paid.getOrderNo(), null, token("usr_buyer", "MEMBER")).status()).isEqualTo(404);
        for (String method : new String[]{"POST", "GET"}) {
            assertThat(http(method, paid.getOrderNo(), null, token("another", "MEMBER")).status()).isEqualTo(404);
            assertThat(http(method, id(), null, token("usr_buyer", "MEMBER")).status()).isEqualTo(404);
        }
        assertThat(count("order_refund")).isZero(); assertThat(count("outbox_event")).isZero();
    }

    @Test void realJwtAndMemberRoleAreRequiredForBothRoutes() {
        Order paid = saved(true);
        for (String method : new String[]{"POST", "GET"}) {
            assertThat(http(method, paid.getOrderNo(), null, null).status()).isEqualTo(401);
            assertThat(http(method, paid.getOrderNo(), null, token("usr_buyer", "ADMIN")).status()).isEqualTo(403);
            assertThat(http(method, paid.getOrderNo(), null, token("usr_buyer", "MEMBER", ISSUER, "wrong-audience", Instant.now().plusSeconds(60))).status()).isEqualTo(401);
            assertThat(http(method, paid.getOrderNo(), null, token("usr_buyer", "MEMBER", ISSUER, "esd-api", Instant.now().minusSeconds(120))).status()).isEqualTo(401);
        }
        assertThat(count("order_refund")).isZero();
    }

    @Test void unpaidOrderCannotCreateARefund() {
        Order pending = saved(false);
        assertThat(http("POST", pending.getOrderNo(), null, token("usr_buyer", "MEMBER")).status()).isEqualTo(409);
        assertThat(count("order_refund")).isZero(); assertThat(count("outbox_event")).isZero();
    }

    @Test void expiredAutomaticEligibilityIsAcceptedForReviewWithoutStartingSuspension() {
        Order paid = saved(true); grant(paid); when(clock.instant()).thenReturn(BASE.plusSeconds(14400));
        Response response = http("POST", paid.getOrderNo(), null, token("usr_buyer", "MEMBER"));
        assertThat(response.status()).isEqualTo(202); assertThat(response.data().path("status").asText()).isEqualTo("PENDING_REVIEW");
        assertThat(count("outbox_event")).isZero();
    }

    @Test void concurrentHttpRequestsCreateOneRefundAndOneSuspensionEvent() throws Exception {
        Order paid = saved(true); grant(paid); String bearer = token("usr_buyer", "MEMBER");
        CountDownLatch start = new CountDownLatch(1); ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Response> first = pool.submit(() -> { start.await(); return http("POST", paid.getOrderNo(), null, bearer); });
            Future<Response> second = pool.submit(() -> { start.await(); return http("POST", paid.getOrderNo(), null, bearer); });
            start.countDown(); Response one = first.get(15, TimeUnit.SECONDS); Response two = second.get(15, TimeUnit.SECONDS);
            assertThat(one.status()).isEqualTo(202); assertThat(two.status()).isEqualTo(202);
            assertThat(one.data()).isEqualTo(two.data()); assertThat(count("order_refund")).isEqualTo(1);
            assertThat(count("outbox_event")).isEqualTo(1);
        } finally { start.countDown(); pool.shutdownNow(); }
    }

    @Test void outboxFailureRollsBackTheHttpReceiptAndSameRequestCanRecover() {
        Order paid = saved(true); grant(paid);
        new TransactionTemplate(transactions).executeWithoutResult(tx ->
                doAnswer(call -> { call.callRealMethod(); throw new IllegalStateException("after suspension outbox insert"); })
                        .when(outbox).record(anyString(), anyString(), any(DomainEvent.class)));
        assertThat(http("POST", paid.getOrderNo(), null, token("usr_buyer", "MEMBER")).status()).isEqualTo(500);
        assertThat(count("order_refund")).isZero(); assertThat(count("outbox_event")).isZero();
        reset(outbox);
        assertThat(http("POST", paid.getOrderNo(), null, token("usr_buyer", "MEMBER")).status()).isEqualTo(202);
        assertThat(count("order_refund")).isEqualTo(1); assertThat(count("outbox_event")).isEqualTo(1);
    }

    private Order saved(boolean paid) {
        Order order = order(); ReflectionTestUtils.setField(order, "createdAt", BASE.minusSeconds(7200));
        if (paid) order.apply(new PaymentResult(id(), BASE.minusSeconds(3600), order.getOrderNo(), order.getRequestEventId(), id(),
                order.getBuyerSubject(), order.getAmount(), order.getCurrency(), OrderStatus.PAID, null, null));
        return orders.saveAndFlush(order);
    }
    private void grant(Order paid) {
        applyGrant.execute(new LicenseGrant(id(), BASE.minusSeconds(30), id(), paid.getOrderNo(), paid.getBuyerSubject(),
                paid.getProductId(), paid.getProductCode(), paid.getPaymentId()));
    }
    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
    private String path(String orderNo) { return "/api/v1/orders/" + orderNo + "/refund"; }
    private Response http(String method, String orderNo, Object body, String bearer) {
        RestClient.RequestBodySpec request = RestClient.create("http://127.0.0.1:" + port).method(HttpMethod.valueOf(method)).uri(path(orderNo));
        if (bearer != null) request.header("Authorization", "Bearer " + bearer);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).body(body);
        return request.exchange((sent, reply) -> new Response(reply.getStatusCode().value(), reply.getHeaders().getFirst("Location"), mapper.readTree(reply.getBody())));
    }
    private record Response(int status, String location, JsonNode body) { JsonNode data() { return body.path("data"); } }
}
