package com.stove.payment;

import static com.stove.payment.PaymentFixtures.id;
import static com.stove.payment.PaymentFixtures.request;
import static com.stove.payment.PortOneFixtures.CHANNEL;
import static com.stove.payment.PortOneFixtures.SECRET;
import static com.stove.payment.PortOneFixtures.STORE;
import static com.stove.payment.PortOneFixtures.sign;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.stove.common.testcontainers.InfraContainers;
import com.stove.payment.core.domain.Payment;
import com.stove.payment.core.domain.PaymentOrderRequest;
import com.stove.payment.core.domain.PaymentRepository;
import com.stove.payment.core.domain.PaymentStatus;
import com.stove.payment.core.domain.PgResult;
import com.stove.payment.core.domain.PortOneWebhookNotice;
import com.stove.payment.core.port.PortOneWebhookReceipts;
import com.stove.payment.core.service.ReceiveOrderPayment;
import com.stove.payment.core.service.ResolvePayment;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "stove.payment.pg.provider=PORTONE_TEST", "stove.payment.processing-enabled=false",
        "stove.outbox.relay-enabled=false", "spring.kafka.listener.auto-startup=false"})
@Import({InfraContainers.MySql.class, InfraContainers.Kafka.class})
class PortOneWebhookIntegrationTest {
    private static final Instant CHECKED_AT = Payment.now();
    private static final Map<String, String> PG_ROWS = new ConcurrentHashMap<>();
    private static final AtomicBoolean PG_DOWN = new AtomicBoolean();
    private static final AtomicInteger PG_READS = new AtomicInteger();
    private static final AtomicInteger PG_WRITES = new AtomicInteger();
    private static final HttpServer PG;
    static {
        try {
            PG = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            PG.createContext("/payments/", exchange -> {
                boolean get = "GET".equals(exchange.getRequestMethod());
                if (get) PG_READS.incrementAndGet(); else PG_WRITES.incrementAndGet();
                String paymentId = exchange.getRequestURI().getPath().substring("/payments/".length());
                String body = PG_ROWS.get(paymentId);
                int status = !get ? 405 : PG_DOWN.get() ? 503 : body == null ? 404 : 200;
                byte[] bytes = (status == 200 ? body : "{}").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                try (OutputStream output = exchange.getResponseBody()) { output.write(bytes); }
            });
            PG.start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> PG.stop(0)));
        } catch (Exception failure) { throw new ExceptionInInitializerError(failure); }
    }

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("stove.payment.portone.base-url", () -> "http://127.0.0.1:" + PG.getAddress().getPort());
        registry.add("stove.payment.portone.api-secret", () -> "test-api-secret");
        registry.add("stove.payment.portone.store-id", () -> STORE);
        registry.add("stove.payment.portone.channel-key", () -> CHANNEL);
        registry.add("stove.payment.portone.webhook-secret", () -> SECRET);
        registry.add("stove.payment.portone.webhook-url", () -> "https://payment-test.example/api/v1/payments/webhooks/portone");
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> PaymentIntegrationTestBase.ISSUER);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () ->
                "http://127.0.0.1:" + PaymentIntegrationTestBase.JWKS.getAddress().getPort() + "/jwks");
    }

    @Autowired ReceiveOrderPayment receive;
    @Autowired ResolvePayment resolve;
    @Autowired PaymentRepository payments;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean Clock clock;
    @MockitoSpyBean PortOneWebhookReceipts receipts;
    @LocalServerPort int port;

    @BeforeEach void initialize() {
        clear();
        when(clock.instant()).thenReturn(CHECKED_AT);
    }

    @AfterEach void clear() {
        reset(receipts);
        jdbc.update("DELETE FROM payment_pg_webhook");
        jdbc.update("DELETE FROM refund_execution");
        jdbc.update("DELETE FROM purchase_payment");
        jdbc.update("DELETE FROM processed_event");
        jdbc.update("DELETE FROM outbox_event");
        PG_ROWS.clear(); PG_DOWN.set(false); PG_READS.set(0); PG_WRITES.set(0);
    }

    @Test void webhookCanArriveBeforeTheBrowserAndOnlyServerLookupConfirmsPaymentOnce() {
        Payment payment = pending();
        defer(payment, CHECKED_AT.plusSeconds(600));
        byte[] body = notice(payment, "Transaction.Paid");
        assertThat(post("msg_paid", body)).isEqualTo(200);
        assertThat(count("payment_pg_webhook")).isEqualTo(1);
        assertThat(stored(payment).getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(stored(payment).getNextAttemptAt()).isEqualTo(CHECKED_AT);
        assertThat(PG_READS.get()).isZero();
        assertThat(count("outbox_event")).isZero();

        resolve.execute();
        Payment confirmed = stored(payment);
        assertThat(confirmed.getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(confirmed.getProviderTransactionId()).isEqualTo("pg-" + payment.getPaymentId());
        assertThat(confirmed.getPgFeeAmount()).isNull();
        assertThat(count("outbox_event")).isEqualTo(1);
        assertThat(post("msg_paid", body)).isEqualTo(200);
        assertThat(post("msg_late_ready", notice(payment, "Transaction.Ready"))).isEqualTo(200);
        resolve.execute(payment.getPaymentId());
        assertThat(stored(payment).supplierResult()).isEqualTo(confirmed.supplierResult());
        assertThat(count("outbox_event")).isEqualTo(1);
        assertThat(count("payment_pg_webhook")).isEqualTo(2);
        assertThat(PG_WRITES.get()).isZero();
    }

    @Test void pollingConfirmsThePurchaseWhenNoWebhookOrBrowserResponseArrives() {
        Payment payment = pending();
        resolve.execute();
        assertThat(stored(payment).getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(count("payment_pg_webhook")).isZero();
        assertThat(count("outbox_event")).isEqualTo(1);
        assertThat(PG_WRITES.get()).isZero();
    }

    @Test void receiptAndWakeupRollBackTogetherAndTheSameDeliveryCanResume() {
        Payment payment = pending();
        Instant due = CHECKED_AT.plusSeconds(600);
        defer(payment, due);
        byte[] body = notice(payment, "Transaction.Paid");
        doAnswer(call -> { call.callRealMethod(); throw new TransientDataAccessResourceException("fixture failure after receipt"); })
                .when(receipts).save(any(PortOneWebhookNotice.class), any(Instant.class));
        assertThat(post("msg_rollback", body)).isEqualTo(500);
        assertThat(count("payment_pg_webhook")).isZero();
        assertThat(stored(payment).getNextAttemptAt()).isEqualTo(due);
        reset(receipts);
        assertThat(post("msg_rollback", body)).isEqualTo(200);
        assertThat(count("payment_pg_webhook")).isEqualTo(1);
        assertThat(stored(payment).getNextAttemptAt()).isEqualTo(CHECKED_AT);
    }

    @Test void concurrentIdenticalWebhooksHaveOneDurableReceipt() throws Exception {
        Payment payment = pending();
        byte[] body = notice(payment, "Transaction.Paid");
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            List<Future<Integer>> responses = new ArrayList<>();
            for (int index = 0; index < 3; index++) responses.add(pool.submit(() -> { start.await(); return post("msg_race", body); }));
            start.countDown();
            for (Future<Integer> response : responses) assertThat(response.get(15, TimeUnit.SECONDS)).isEqualTo(200);
            assertThat(count("payment_pg_webhook")).isEqualTo(1);
            assertThat(stored(payment).getStatus()).isEqualTo(PaymentStatus.PENDING);
            assertThat(PG_READS.get()).isZero();
        } finally { pool.shutdownNow(); }
    }

    @Test void malformedUnauthenticatedExpiredAndWrongStoreNotificationsDoNotReachBusinessState() {
        Payment payment = pending();
        byte[] body = notice(payment, "Transaction.Paid");
        assertThat(post("msg_invalid", body, "v1,bad", Long.toString(CHECKED_AT.getEpochSecond()))).isEqualTo(401);
        String old = Long.toString(CHECKED_AT.getEpochSecond() - 301);
        assertThat(post("msg_old", body, sign("msg_old", old, body), old)).isEqualTo(401);
        assertThat(post("msg_store", new String(body, StandardCharsets.UTF_8).replace(STORE, "store-other")
                .getBytes(StandardCharsets.UTF_8))).isEqualTo(400);
        assertThat(post("msg_json", "not-json".getBytes(StandardCharsets.UTF_8))).isEqualTo(400);
        assertThat(post("msg_large", new byte[65537])).isEqualTo(413);
        assertThat(count("payment_pg_webhook")).isZero();
        assertThat(count("outbox_event")).isZero();
        assertThat(PG_READS.get()).isZero();
        assertThat(stored(payment).getStatus()).isEqualTo(PaymentStatus.PENDING);
    }

    @Test void changedWebhookIdReuseAndMissingPurchaseCannotBeInventedIntoWork() {
        Payment payment = pending();
        assertThat(post("msg_bound", notice(payment, "Transaction.Paid"))).isEqualTo(200);
        assertThat(post("msg_bound", notice(payment, "Transaction.Ready"))).isEqualTo(409);
        byte[] unknown = new String(notice(payment, "Transaction.Paid"), StandardCharsets.UTF_8)
                .replace(payment.getPaymentId(), id()).getBytes(StandardCharsets.UTF_8);
        assertThat(post("msg_unknown", unknown)).isEqualTo(404);
        assertThat(count("payment_pg_webhook")).isEqualTo(1);
        assertThat(count("purchase_payment")).isEqualTo(1);
    }

    @Test void anUnavailableProviderStaysPendingAndAWebhookCanWakeARecheckAfterRecovery() {
        Payment payment = pending();
        PG_DOWN.set(true);
        resolve.execute(payment.getPaymentId());
        assertThat(stored(payment).getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(count("outbox_event")).isZero();
        PG_DOWN.set(false);
        assertThat(post("msg_recovered", notice(payment, "Transaction.Paid"))).isEqualTo(200);
        resolve.execute();
        assertThat(stored(payment).getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(count("outbox_event")).isEqualTo(1);
    }

    @Test void anotherProvidersPendingRowsCannotStarveThisProvidersPollingBatch() {
        for (int index = 0; index < 25; index++) payments.saveAndFlush(Payment.receive(request(), "SIMULATED"));
        Payment payment = pending();
        resolve.execute();
        assertThat(stored(payment).getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(PG_READS.get()).isEqualTo(1);
        assertThat(PG_WRITES.get()).isZero();
    }

    @Test void unsupportedSignedEventsAreAcknowledgedWithoutInventingAReceipt() {
        assertThat(post("msg_future", "{\"type\":\"Transaction.FutureEvent\"}".getBytes(StandardCharsets.UTF_8))).isEqualTo(200);
        assertThat(count("payment_pg_webhook")).isZero();
    }

    @Test void checkoutReadsTheOwnedFixedPurchaseAndPublicSdkSettingsWithoutCallingTheProvider() {
        PaymentOrderRequest original = request();
        Payment payment = receive.execute(new PaymentOrderRequest(original.eventId(), original.occurredAt(),
                original.orderNo(), original.buyerSubject(), original.productId(), original.productCode(),
                original.title(), original.contentVersion(), 10000, 1000, 9000, "KRW"));
        Payment before = stored(payment);
        String token = PaymentIntegrationTestBase.token(payment.getBuyerSubject(), "MEMBER");
        CheckoutReply reply = checkout(payment.getOrderNo(), token);
        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.cacheControl()).contains("no-store");
        JsonNode data = reply.body().path("data");
        assertThat(data.path("paymentId").asText()).isEqualTo(payment.getPaymentId());
        assertThat(data.path("totalAmount").asLong()).isEqualTo(9000);
        assertThat(data.path("currency").asText()).isEqualTo("CURRENCY_KRW");
        assertThat(data.path("payMethod").asText()).isEqualTo("EASY_PAY");
        assertThat(data.path("storeId").asText()).isEqualTo(STORE);
        assertThat(data.path("channelKey").asText()).isEqualTo(CHANNEL);
        assertThat(data.path("customData").path("orderNo").asText()).isEqualTo(payment.getOrderNo());
        assertThat(data.path("noticeUrls").get(0).asText()).isEqualTo("https://payment-test.example/api/v1/payments/webhooks/portone");
        assertThat(reply.body().toString()).doesNotContain("test-api-secret", SECRET, "webhookSecret", "apiSecret");
        assertThat(checkout(payment.getOrderNo(), token).body()).isEqualTo(reply.body());
        assertThat(stored(payment).getUpdatedAt()).isEqualTo(before.getUpdatedAt());
        assertThat(stored(payment).getNextAttemptAt()).isEqualTo(before.getNextAttemptAt());
        assertThat(stored(payment).getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(count("purchase_payment")).isEqualTo(1);
        assertThat(count("outbox_event")).isZero();
        assertThat(PG_READS.get()).isZero();
        assertThat(PG_WRITES.get()).isZero();
    }

    @Test void checkoutRequiresAMemberAndHidesAnotherBuyersOrNotYetReceivedPurchase() {
        Payment payment = pending();
        assertThat(checkout(payment.getOrderNo(), null).status()).isEqualTo(401);
        assertThat(checkout(payment.getOrderNo(), "invalid-token").status()).isEqualTo(401);
        assertThat(checkout(payment.getOrderNo(), PaymentIntegrationTestBase.token(payment.getBuyerSubject(), "SELLER")).status()).isEqualTo(403);
        assertThat(checkout(payment.getOrderNo(), PaymentIntegrationTestBase.token("another-buyer", "MEMBER")).status()).isEqualTo(404);
        assertThat(checkout(id(), PaymentIntegrationTestBase.token(payment.getBuyerSubject(), "MEMBER")).status()).isEqualTo(404);
        assertThat(count("purchase_payment")).isEqualTo(1);
        assertThat(PG_READS.get()).isZero();
        assertThat(count("outbox_event")).isZero();
    }

    @Test void checkoutCannotReopenAFinalPurchaseOrMoveASimulatedPurchaseToPortOne() {
        Payment payment = pending();
        String token = PaymentIntegrationTestBase.token(payment.getBuyerSubject(), "MEMBER");
        assertThat(checkout(payment.getOrderNo(), token).status()).isEqualTo(200);
        assertThat(post("msg_checkout_paid", notice(payment, "Transaction.Paid"))).isEqualTo(200);
        resolve.execute(payment.getPaymentId());
        assertThat(checkout(payment.getOrderNo(), token).status()).isEqualTo(409);
        assertThat(stored(payment).getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(PG_READS.get()).isEqualTo(1);
        assertThat(count("outbox_event")).isEqualTo(1);

        Payment failed = Payment.receive(request(), "PORTONE_TEST");
        failed.confirm(new PgResult("PORTONE_TEST", failed.getPaymentId(), failed.getOrderNo(), failed.getAmount(),
                "KRW", id(), CHECKED_AT, PgResult.Decision.DECLINED, "DECLINED", "Fixture final decline"), id(), CHECKED_AT);
        payments.saveAndFlush(failed);
        assertThat(checkout(failed.getOrderNo(), token).status()).isEqualTo(409);
        Payment simulated = payments.saveAndFlush(Payment.receive(request(), "SIMULATED"));
        assertThat(checkout(simulated.getOrderNo(), token).status()).isEqualTo(409);
        assertThat(PG_READS.get()).isEqualTo(1);
        assertThat(PG_WRITES.get()).isZero();
    }

    private record CheckoutReply(int status, JsonNode body, String cacheControl) { }

    private CheckoutReply checkout(String orderNo, String token) {
        RestClient.RequestHeadersSpec<?> request = RestClient.create("http://127.0.0.1:" + port).get()
                .uri("/api/v1/payments/{orderNo}/checkout", orderNo);
        if (token != null) request.header("Authorization", "Bearer " + token);
        return request.exchange((outgoing, response) -> new CheckoutReply(response.getStatusCode().value(),
                response.bodyTo(JsonNode.class), response.getHeaders().getCacheControl()));
    }

    private Payment pending() {
        Payment payment = receive.execute(request());
        PG_ROWS.put(payment.getPaymentId(), paid(payment));
        return payment;
    }

    private void defer(Payment payment, Instant at) {
        new TransactionTemplate(transactions).executeWithoutResult(tx ->
                payments.lockByPaymentId(payment.getPaymentId()).orElseThrow().defer(at, "WAITING_FOR_PG"));
    }

    private Payment stored(Payment payment) { return payments.findById(payment.getId()).orElseThrow(); }
    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }

    private int post(String messageId, byte[] body) {
        String timestamp = Long.toString(CHECKED_AT.getEpochSecond());
        return post(messageId, body, sign(messageId, timestamp, body), timestamp);
    }

    private int post(String messageId, byte[] body, String signature, String timestamp) {
        return RestClient.create("http://127.0.0.1:" + port).post().uri("/api/v1/payments/webhooks/portone")
                .contentType(MediaType.APPLICATION_JSON).header("webhook-id", messageId)
                .header("webhook-timestamp", timestamp).header("webhook-signature", signature).body(body)
                .exchange((request, response) -> response.getStatusCode().value());
    }

    private static byte[] notice(Payment payment, String type) {
        return ("{\"type\":\"" + type + "\",\"timestamp\":\"" + CHECKED_AT + "\",\"data\":{\"storeId\":\"" + STORE
                + "\",\"paymentId\":\"" + payment.getPaymentId() + "\",\"transactionId\":\"transaction-test\"}}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static String paid(Payment payment) {
        return """
                {"status":"PAID","id":"%s","storeId":"%s","version":"V2","currency":"KRW",
                 "channel":{"type":"TEST","key":"%s","pgProvider":"TOSSPAY_V2"},
                 "amount":{"total":%d,"paid":%d,"discount":0,"cancelled":0},
                 "customData":"{\\"orderNo\\":\\"%s\\"}","paidAt":"%s","pgTxId":"pg-%s"}
                """.formatted(payment.getPaymentId(), STORE, CHANNEL, payment.getAmount(), payment.getAmount(),
                payment.getOrderNo(), CHECKED_AT, payment.getPaymentId());
    }
}
