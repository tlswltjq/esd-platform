package com.stove.settlement;

import static com.stove.settlement.SettlementFixtures.NOW;
import static com.stove.settlement.SettlementFixtures.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stove.common.messaging.inbox.ProcessedEventGuard;
import com.stove.common.web.error.BusinessException;
import com.stove.settlement.core.domain.ConfirmedRefund;
import com.stove.settlement.core.domain.PaidOrder;
import com.stove.settlement.core.domain.SellerMonthlySettlement;
import com.stove.settlement.core.service.CloseSellerMonth;
import com.stove.settlement.core.service.RecordPaidSale;
import com.stove.settlement.core.service.RecordConfirmedRefund;
import com.stove.settlement.core.service.SynchronizePgTransaction;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "stove.settlement.pg-provider=PORTONE_TEST", "stove.outbox.relay-enabled=false",
        "stove.messaging.ops.enabled=false", "spring.kafka.listener.auto-startup=false"})
class PortOneSettlementIntegrationTest extends SettlementIntegrationTestBase {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AtomicReference<String> RESPONSE = new AtomicReference<>();
    private static final AtomicInteger HTTP_STATUS = new AtomicInteger(200);
    private static final AtomicInteger PG_GETS = new AtomicInteger();
    private static final AtomicInteger CONTRACT_ERRORS = new AtomicInteger();
    private static final HttpServer SUPPLIER;
    static {
        try {
            SUPPLIER = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            SUPPLIER.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                int status = 200;
                String response;
                if (!"GET".equals(exchange.getRequestMethod())) {
                    CONTRACT_ERRORS.incrementAndGet(); status = 405; response = "{}";
                } else if (path.startsWith("/payments/")) {
                    PG_GETS.incrementAndGet();
                    if (!"PortOne test-read-secret".equals(exchange.getRequestHeaders().getFirst("Authorization"))
                            || !"storeId=store-test".equals(exchange.getRequestURI().getRawQuery())) CONTRACT_ERRORS.incrementAndGet();
                    status = HTTP_STATUS.get(); response = RESPONSE.get();
                } else if (path.equals("/internal/v1/products/game-1/owner")) {
                    if (!"test-catalog-key".equals(exchange.getRequestHeaders().getFirst("X-Settlement-Service-Key"))) CONTRACT_ERRORS.incrementAndGet();
                    response = "{\"success\":true,\"data\":{\"productId\":1,\"productCode\":\"game-1\",\"ownerSubject\":\"usr_seller\"}}";
                } else { CONTRACT_ERRORS.incrementAndGet(); status = 404; response = "{}"; }
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                try (OutputStream out = exchange.getResponseBody()) { out.write(bytes); }
            });
            SUPPLIER.start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> SUPPLIER.stop(0)));
        } catch (Exception failure) { throw new ExceptionInInitializerError(failure); }
    }

    @DynamicPropertySource static void supplier(DynamicPropertyRegistry properties) {
        properties.add("stove.settlement.portone.base-url", () -> "http://127.0.0.1:" + SUPPLIER.getAddress().getPort());
        properties.add("stove.settlement.portone.api-secret", () -> "test-read-secret");
        properties.add("stove.settlement.portone.store-id", () -> "store-test");
        properties.add("stove.settlement.portone.channel-key", () -> "channel-test");
        properties.add("stove.settlement.catalog-base-url", () -> "http://127.0.0.1:" + SUPPLIER.getAddress().getPort());
        properties.add("stove.settlement.pg-api-key", () -> "");
    }

    @Autowired RecordPaidSale record;
    @Autowired RecordConfirmedRefund refunds;
    @Autowired CloseSellerMonth close;
    @Autowired SynchronizePgTransaction synchronize;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean Clock clock;
    @MockitoSpyBean ProcessedEventGuard guard;
    @LocalServerPort int port;

    @BeforeEach void clean() {
        for (String table : List.of("settlement_refund_cancellation","settlement_refund","pg_receipt_item", "pg_receipt", "settlement_sale", "seller_monthly_settlement",
                "settlement_pg_fee", "settlement_pg_transaction", "processed_event")) jdbc.update("DELETE FROM " + table);
        PG_GETS.set(0); CONTRACT_ERRORS.set(0); HTTP_STATUS.set(200); RESPONSE.set("{}");
        when(clock.instant()).thenReturn(NOW);
    }
    @AfterEach void onlyExpectedReadOnlySupplierCalls() { assertThat(CONTRACT_ERRORS.get()).isZero(); }

    PaidOrder order() {
        return new PaidOrder(id(), Instant.parse("2026-09-01T00:00:00Z"), id(), id(), id(), id(),
                "usr_buyer", 1L, "game-1", "Frozen title", 1, 9000, "KRW");
    }
    ObjectNode approved(PaidOrder order) {
        ObjectNode body = JSON.createObjectNode().put("id", order.paymentId()).put("storeId", "store-test").put("version", "V2")
                .put("status", "PAID").put("currency", "KRW").put("pgTxId", "pg-" + order.paymentId())
                .put("paidAt", "2026-08-31T23:59:59.123456789Z")
                .put("customData", JSON.createObjectNode().put("orderNo", order.orderNo()).toString());
        body.putObject("channel").put("type", "TEST").put("key", "channel-test").put("pgProvider", "TOSSPAY_V2");
        body.putObject("amount").put("total", 9000).put("paid", 8976).put("discount", 24).put("cancelled", 0);
        return body;
    }
    ConfirmedRefund confirmation(PaidOrder input) {
        return new ConfirmedRefund(id(), NOW.minusSeconds(2), id(), id(), id(), input.orderNo(), input.paymentId(),
                input.buyerSubject(), input.amount(), input.currency(), "PG_QUERY_UNATTRIBUTED", "pg-" + input.paymentId(), List.of(id()));
    }
    ObjectNode cancelled(PaidOrder input, ConfirmedRefund confirmation) {
        ObjectNode body = approved(input).put("status", "CANCELLED").put("cancelledAt", confirmation.occurredAt().minusSeconds(1).toString());
        ((ObjectNode) body.path("amount")).put("cancelled", input.amount());
        body.putArray("cancellations").addObject().put("id", confirmation.cancellationIds().getFirst())
                .put("status", "SUCCEEDED").put("totalAmount", input.amount()).putNull("cancelledAt");
        return body;
    }
    @Test void independentlyReadCancellationReversesOriginalFundingAndPreservesTheClosedSaleMonth() {
        PaidOrder input = order(); RESPONSE.set(approved(input).toString()); record.execute(input); close.execute("usr_seller", "2026-09");
        Map<String, Object> sale = jdbc.queryForMap("SELECT * FROM settlement_sale");
        Map<String, Object> closed = jdbc.queryForMap("SELECT * FROM seller_monthly_settlement");
        Map<String, Object> funding = jdbc.queryForMap("SELECT * FROM settlement_pg_transaction");
        ConfirmedRefund confirmation = confirmation(input); RESPONSE.set(cancelled(input, confirmation).toString());
        long firstId = refunds.execute(confirmation).getId();
        assertThat(PG_GETS.get()).isEqualTo(2);
        assertThat(jdbc.queryForMap("SELECT * FROM settlement_sale")).isEqualTo(sale);
        assertThat(jdbc.queryForMap("SELECT * FROM settlement_pg_transaction")).isEqualTo(funding);
        assertThat(jdbc.queryForMap("SELECT * FROM seller_monthly_settlement WHERE settlement_month='2026-09'")).isEqualTo(closed);
        assertThat(jdbc.queryForMap("SELECT amount,platform_fee_amount,seller_payout_amount,settlement_month FROM settlement_refund"))
                .containsEntry("amount", 9000L).containsEntry("platform_fee_amount", 900L).containsEntry("seller_payout_amount", 8100L)
                .containsEntry("settlement_month", "2026-10");
        assertThat(jdbc.queryForMap("SELECT provider,cancelled_amount,cancelled_at FROM settlement_refund_cancellation"))
                .containsEntry("provider", "PORTONE_TEST").containsEntry("cancelled_amount", 9000L).containsEntry("cancelled_at", null);
        assertThat(jdbc.queryForObject("SELECT seller_payout_amount-refunded_seller_payout_amount FROM seller_monthly_settlement WHERE settlement_month='2026-10'", Long.class)).isEqualTo(-8100);
        JsonNode finance = http("GET", "/api/v1/settlements/months?month=2026-10", "ADMIN").body().path("data").path("content").get(0);
        assertThat(finance.path("pgCostStatus").asText()).isEqualTo("UNCONFIRMED");
        assertThat(finance.path("pgFeeAmount").isNull()).isTrue(); assertThat(finance.path("platformNetAmount").isNull()).isTrue();
        assertThat(count("settlement_pg_fee")).isZero();
        HTTP_STATUS.set(503); assertThat(refunds.execute(confirmation).getId()).isEqualTo(firstId);
        assertThat(PG_GETS.get()).isEqualTo(2); assertThat(count("settlement_refund")).isEqualTo(1);
    }

    @Test void delayedOriginalSaleCanBeRecoveredFromAFullyCancelledApprovalBeforeRefundPosting() {
        PaidOrder input = order(); ConfirmedRefund confirmation = confirmation(input); RESPONSE.set(cancelled(input, confirmation).toString());
        assertThatThrownBy(() -> refunds.execute(confirmation)).isInstanceOf(BusinessException.class);
        assertThat(PG_GETS.get()).isZero(); noAllocation();
        record.execute(input); refunds.execute(confirmation);
        assertThat(PG_GETS.get()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT SUM(paid_amount-refund_amount) FROM seller_monthly_settlement", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT SUM(seller_payout_amount-refunded_seller_payout_amount) FROM seller_monthly_settlement", Long.class)).isZero();
        assertThat(count("settlement_refund")).isEqualTo(1); assertThat(count("processed_event")).isEqualTo(2);
    }

    @Test void unverifiedOrIncompleteCancellationsCannotChangeRefundLedgerMonthOrInbox() {
        PaidOrder input = order(); RESPONSE.set(approved(input).toString()); record.execute(input);
        ConfirmedRefund confirmation = confirmation(input); ObjectNode good = cancelled(input, confirmation);
        Map<String, Object> month = jdbc.queryForMap("SELECT * FROM seller_monthly_settlement");
        List<ObjectNode> invalid = new ArrayList<>();
        invalid.add(good.deepCopy().put("status", "PARTIAL_CANCELLED"));
        invalid.add(good.deepCopy().put("pgTxId", "other"));
        invalid.add(good.deepCopy().put("cancelledAt", "invalid"));
        ObjectNode wrongId = good.deepCopy(); ((ObjectNode) wrongId.path("cancellations").get(0)).put("id", id()); invalid.add(wrongId);
        ObjectNode pending = good.deepCopy(); ((ObjectNode) pending.path("cancellations").get(0)).put("status", "REQUESTED"); invalid.add(pending);
        ObjectNode partial = good.deepCopy(); ((ObjectNode) partial.path("cancellations").get(0)).put("totalAmount", 8999); invalid.add(partial);
        ObjectNode textAmount = good.deepCopy(); ((ObjectNode) textAmount.path("amount")).put("cancelled", "9000"); invalid.add(textAmount);
        ObjectNode funding = good.deepCopy(); ((ObjectNode) funding.path("amount")).put("paid", 8975).put("discount", 25); invalid.add(funding);
        ObjectNode duplicate = good.deepCopy(); duplicate.withArray("cancellations").add(duplicate.path("cancellations").get(0).deepCopy()); invalid.add(duplicate);
        for (ObjectNode bad : invalid) {
            RESPONSE.set(bad.toString()); assertThatThrownBy(() -> refunds.execute(confirmation)).isInstanceOf(BusinessException.class);
            assertThat(count("settlement_refund")).isZero(); assertThat(count("settlement_refund_cancellation")).isZero();
            assertThat(count("processed_event")).isEqualTo(1);
            assertThat(jdbc.queryForMap("SELECT * FROM seller_monthly_settlement")).isEqualTo(month);
        }
        HTTP_STATUS.set(503); RESPONSE.set(good.toString());
        assertThatThrownBy(() -> refunds.execute(confirmation)).isInstanceOf(BusinessException.class);
        assertThat(count("settlement_refund")).isZero(); assertThat(count("processed_event")).isEqualTo(1);
        HTTP_STATUS.set(200); refunds.execute(confirmation); assertThat(count("settlement_refund")).isEqualTo(1);
    }
    int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
    record Reply(int status, JsonNode body) { }
    Reply http(String method, String path, String role) {
        return RestClient.create("http://127.0.0.1:" + port).method(HttpMethod.valueOf(method)).uri(path)
                .header("Authorization", "Bearer " + token(role.equals("ADMIN") ? "usr_admin" : "usr_seller", role))
                .exchange((request, response) -> new Reply(response.getStatusCode().value(), response.bodyTo(JsonNode.class)));
    }
    void noAllocation() {
        for (String table : List.of("settlement_sale", "seller_monthly_settlement", "settlement_pg_transaction", "settlement_pg_fee", "processed_event"))
            assertThat(count(table)).as(table).isZero();
    }

    @Test void discountedApprovalPreservesFundingAndClosesSellerMonthWithoutCost() {
        PaidOrder input = order(); RESPONSE.set(approved(input).toString());
        long saleId = record.execute(input).getId();
        assertThat(jdbc.queryForObject("SELECT seller_payout_amount FROM settlement_sale", Long.class)).isEqualTo(8100);
        assertThat(jdbc.queryForObject("SELECT platform_fee_amount FROM settlement_sale", Long.class)).isEqualTo(900);
        Map<String, Object> funding = jdbc.queryForMap("SELECT funding_total_amount,pg_paid_amount,pg_discount_amount,provider_channel_type,provider_pg_provider,benefit_policy_id FROM settlement_pg_transaction");
        assertThat(funding).containsEntry("funding_total_amount", 9000L).containsEntry("pg_paid_amount", 8976L)
                .containsEntry("pg_discount_amount", 24L).containsEntry("provider_channel_type", "TEST")
                .containsEntry("provider_pg_provider", "TOSSPAY_V2").containsEntry("benefit_policy_id", "TOSSPAY_EXTERNAL_BENEFIT_V1");
        assertThat(count("settlement_pg_fee")).isZero();
        JsonNode admin = http("GET", "/api/v1/settlements/sales", "ADMIN").body().path("data").path("content").get(0);
        assertThat(admin.path("funding").path("pgPaidAmount").asLong()).isEqualTo(8976);
        assertThat(admin.path("pgCostStatus").asText()).isEqualTo("UNCONFIRMED");
        assertThat(admin.path("platformNetAmount").isNull()).isTrue();
        assertThat(http("GET", "/api/v1/settlements/me/sales", "CREATOR").body().toString())
                .doesNotContain("pgPaidAmount", "funding", "providerChannelKey");
        SellerMonthlySettlement closed = close.execute("usr_seller", "2026-09");
        Map<String, Object> month = jdbc.queryForMap("SELECT * FROM seller_monthly_settlement");
        assertThat(closed.getSellerPayoutAmount()).isEqualTo(8100);
        assertThat(http("POST", "/api/v1/settlements/pg-transactions/" + input.paymentId() + "/refresh", "ADMIN").status()).isEqualTo(200);
        assertThat(jdbc.queryForMap("SELECT * FROM seller_monthly_settlement")).isEqualTo(month);
        assertThat(count("settlement_pg_fee")).isZero();
        int beforeDuplicate = PG_GETS.get(); HTTP_STATUS.set(503);
        assertThat(record.execute(input).getId()).isEqualTo(saleId);
        assertThat(PG_GETS.get()).isEqualTo(beforeDuplicate);
        assertThat(close.execute("usr_seller", "2026-09").getClosedAt()).isEqualTo(closed.getClosedAt());
    }

    @Test void unverifiedFundingAndBrokenPurchaseNeverCreateASaleOrInbox() {
        PaidOrder input = order(); ObjectNode good = approved(input);
        for (String field : List.of("id", "storeId", "version", "currency", "pgTxId", "paidAt", "customData")) {
            ObjectNode bad = good.deepCopy(); bad.put(field, field.equals("pgTxId") ? "" : "wrong"); RESPONSE.set(bad.toString());
            assertThatThrownBy(() -> record.execute(input)).as(field).isInstanceOf(BusinessException.class); noAllocation();
        }
        for (String field : List.of("type", "key", "pgProvider")) {
            ObjectNode bad = good.deepCopy(); ((ObjectNode) bad.path("channel")).put(field, "UNKNOWN"); RESPONSE.set(bad.toString());
            assertThatThrownBy(() -> record.execute(input)).as(field).isInstanceOf(BusinessException.class); noAllocation();
        }
        for (String status : List.of("READY", "PAY_PENDING", "FAILED", "CANCELLED", "PARTIAL_CANCELLED")) {
            ObjectNode bad = good.deepCopy(); bad.put("status", status); RESPONSE.set(bad.toString());
            assertThatThrownBy(() -> record.execute(input)).as(status).isInstanceOf(BusinessException.class); noAllocation();
        }
        for (String field : List.of("total", "paid", "discount", "cancelled")) {
            ObjectNode bad = good.deepCopy(); ((ObjectNode) bad.path("amount")).put(field, "9000"); RESPONSE.set(bad.toString());
            assertThatThrownBy(() -> record.execute(input)).as(field).isInstanceOf(BusinessException.class); noAllocation();
            bad = good.deepCopy(); ((ObjectNode) bad.path("amount")).putNull(field); RESPONSE.set(bad.toString());
            assertThatThrownBy(() -> record.execute(input)).as(field).isInstanceOf(BusinessException.class); noAllocation();
        }
        ObjectNode wrongOrder = good.deepCopy().put("customData", JSON.createObjectNode().put("orderNo", id()).toString());
        RESPONSE.set(wrongOrder.toString()); assertThatThrownBy(() -> record.execute(input)).isInstanceOf(BusinessException.class); noAllocation();
        ObjectNode wrongAmounts = good.deepCopy(); ((ObjectNode) wrongAmounts.path("amount")).put("paid", 8975);
        RESPONSE.set(wrongAmounts.toString()); assertThatThrownBy(() -> record.execute(input)).isInstanceOf(BusinessException.class); noAllocation();
        for (String raw : List.of(good + "{}", good.toString().replace("\"status\":\"PAID\"", "\"status\":\"PAID\",\"status\":\"PAID\""))) {
            RESPONSE.set(raw); assertThatThrownBy(() -> record.execute(input)).isInstanceOf(BusinessException.class); noAllocation();
        }
    }

    @Test void aLaterObservationCannotOverwriteVerifiedFundingOrTheClosedMonth() {
        PaidOrder input = order(); ObjectNode good = approved(input); RESPONSE.set(good.toString()); record.execute(input);
        close.execute("usr_seller", "2026-09");
        Map<String, Object> before = jdbc.queryForMap("SELECT * FROM settlement_pg_transaction");
        Map<String, Object> month = jdbc.queryForMap("SELECT * FROM seller_monthly_settlement");
        ((ObjectNode) good.path("amount")).put("paid", 8975).put("discount", 25); RESPONSE.set(good.toString());
        assertThat(http("POST", "/api/v1/settlements/pg-transactions/" + input.paymentId() + "/refresh", "ADMIN").status()).isEqualTo(409);
        assertThat(jdbc.queryForMap("SELECT * FROM settlement_pg_transaction")).isEqualTo(before);
        assertThat(jdbc.queryForMap("SELECT * FROM seller_monthly_settlement")).isEqualTo(month);
    }

    @Test void upstreamAndInboxFailuresCannotLeaveFundingOrInventReceiptEvidence() {
        PaidOrder input = order(); RESPONSE.set(approved(input).toString()); HTTP_STATUS.set(503);
        assertThatThrownBy(() -> record.execute(input)).isInstanceOf(BusinessException.class); noAllocation();
        HTTP_STATUS.set(200);
        new TransactionTemplate(transactions).executeWithoutResult(tx -> doAnswer(call -> {
            call.callRealMethod(); throw new IllegalStateException("after inbox fixture");
        }).when(guard).firstDelivery(anyString(), anyString(), anyString()));
        assertThatThrownBy(() -> record.execute(input)).isInstanceOf(IllegalStateException.class); noAllocation();
        // 미연동 명세는 실제/모의 0원 수령으로 접수되지 않는다.
        Reply receipt = RestClient.create("http://127.0.0.1:" + port).post().uri("/api/v1/settlements/pg-receipts")
                .header("Authorization", "Bearer " + token("usr_admin", "ADMIN")).body(Map.of("providerSettlementId", id()))
                .exchange((request, response) -> new Reply(response.getStatusCode().value(), response.bodyTo(JsonNode.class)));
        assertThat(receipt.status()).isEqualTo(503); assertThat(count("pg_receipt")).isZero();
        assertThat(PG_GETS.get()).isEqualTo(2);
    }
}
