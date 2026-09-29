package com.stove.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.stove.e2e.E2eClient.Response;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

/** Public price, order, simulator payment and ledger must use one frozen promotion snapshot. */
@Order(6)
@DisplayName("운영 할인 — 판매자/플랫폼 부담·환불·월 대사")
class PromotionOperationsE2eTest {
    private static final int DISCOUNT = 2_000;
    private static final int CHARGE = Journey.PRICE - DISCOUNT;
    private static final int PARTNER_FEE_RATE = 30;

    private static Response create(String owner) {
        String prefix = "/api/v1/promotions/" + owner + "/products/" + Journey.productId();
        return Stove.gateway.post(prefix, Map.of("discountPerUnit", DISCOUNT,
                "startsAt", Instant.now().minusSeconds(10).toString(),
                "endsAt", Instant.now().plusSeconds(1800).toString()),
                "seller".equals(owner) ? Journey.asCreator() : Journey.asAdmin());
    }

    private static String pay() {
        Response order = Stove.gateway.post("/api/v1/orders", Map.of(
                "items", List.of(Map.of("productId", Journey.productId(), "quantity", 1)),
                "expectedAmount", CHARGE), Journey.asMember(Journey.MEMBER));
        assertThat(order.status()).as("%s", order).isEqualTo(200);
        assertThat(order.data().path("totalAmount").asInt()).isEqualTo(CHARGE);
        assertThat(order.data().path("lines").get(0).path("listUnitPrice").asInt()).isEqualTo(Journey.PRICE);
        assertThat(order.data().path("lines").get(0).path("discountPerUnit").asInt()).isEqualTo(DISCOUNT);
        int basis = "PLATFORM".equals(order.data().path("lines").get(0)
                .path("discountBearer").asText()) ? Journey.PRICE : CHARGE;
        assertThat(order.data().path("lines").get(0).path("settlementBasis").asInt()).isEqualTo(basis);
        assertThat(order.data().path("lines").get(0).path("feeAmount").asInt())
                .isEqualTo(basis * PARTNER_FEE_RATE / 100);
        assertThat(order.data().path("lines").get(0).path("sellerPayout").asInt())
                .isEqualTo(basis * (100 - PARTNER_FEE_RATE) / 100);
        String orderNo = order.data().path("orderNo").asText();

        Await.untilResponse("할인 주문 결제 대기", () -> Stove.gateway.get(
                "/api/v1/payments/" + orderNo, Journey.asMember(Journey.MEMBER)),
                r -> "READY".equals(r.data().path("status").asText()));
        Response prepared = Stove.gateway.post("/api/v1/payments/" + orderNo + "/prepare",
                Map.of("method", "CARD"), Journey.asMember(Journey.MEMBER));
        assertThat(prepared.status()).as("%s", prepared).isEqualTo(200);
        assertThat(prepared.data().path("amount").asInt()).isEqualTo(CHARGE);
        Response approved = Stove.gateway.post("/api/v1/payments/simulator/" + orderNo + "/approve",
                null, Journey.asAdmin());
        assertThat(approved.status()).as("%s", approved).isEqualTo(200);
        assertThat(approved.data().path("amount").asInt()).isEqualTo(CHARGE);
        Await.untilResponse("할인 주문 매출 원장", () -> ledger(orderNo),
                r -> !r.itemWhere("recordType", "SALE").isMissingNode());
        return orderNo;
    }

    private static Response ledger(String orderNo) {
        return Stove.gateway.get("/api/v1/settlements/orders/" + orderNo, Journey.asAdmin());
    }

    private static void refund(String orderNo) {
        Response result = Stove.gateway.post("/api/v1/payments/simulator/" + orderNo + "/refund",
                null, Journey.asAdmin());
        assertThat(result.status()).as("%s", result).isEqualTo(200);
        Await.untilResponse("할인 주문 환불 원장", () -> ledger(orderNo),
                r -> !r.itemWhere("recordType", "REFUND").isMissingNode());
    }

    private static void stop(String owner, long id) {
        Response result = Stove.gateway.post("/api/v1/promotions/" + owner + "/" + id + "/stop",
                null, "seller".equals(owner) ? Journey.asCreator() : Journey.asAdmin());
        assertThat(result.status()).as("%s", result).isEqualTo(200);
    }

    private static void displayedPrice(int expected) {
        String code = Journey.PRODUCT_CODE;
        Await.untilResponse("catalog 행사 가격", () -> Stove.gateway.get("/api/v1/products/by-code/" + code),
                r -> r.status() == 200 && r.data().path("price").asInt() == expected);
        Await.untilResponse("store 행사 가격", () -> Stove.gateway.get("/api/v1/storefront/products/" + code),
                r -> r.status() == 200 && r.data().path("price").asInt() == expected);
    }

    @Test
    @Order(1)
    void sellerFundedPromotionUsesNetChargeAsSettlementBasis() {
        assertThat(Stove.gateway.post("/api/v1/promotions/seller/products/" + Journey.productId(),
                Map.of(), Journey.asMember(Journey.MEMBER)).status()).isEqualTo(403);
        Response created = create("seller");
        assertThat(created.status()).as("%s", created).isEqualTo(200);
        long id = created.data().path("id").asLong();
        assertThat(create("platform").status()).isEqualTo(409);
        displayedPrice(CHARGE);

        String orderNo = pay();
        var sale = ledger(orderNo).itemWhere("recordType", "SALE");
        assertThat(sale.path("paidAmount").asInt()).isEqualTo(CHARGE);
        assertThat(sale.path("grossAmount").asInt()).isEqualTo(CHARGE);
        assertThat(sale.path("discountAmount").asInt()).isEqualTo(DISCOUNT);
        assertThat(sale.path("platformExpense").asInt()).isZero();
        assertThat(sale.path("feeAmount").asInt()).isEqualTo(CHARGE * PARTNER_FEE_RATE / 100);
        assertThat(sale.path("promotionId").asLong()).isEqualTo(id);

        Response own = Stove.gateway.get("/api/v1/settlements/me/ledger?month=" + YearMonth.now(),
                Journey.asCreator());
        assertThat(own.status()).as("%s", own).isEqualTo(200);
        assertThat(own.data().toString()).contains(orderNo);
        refund(orderNo);
        stop("seller", id);
        displayedPrice(Journey.PRICE);
    }

    @Test
    @Order(2)
    void platformFundedPromotionPreservesSellerBasisAndReconcilesLateRefund() {
        Response created = create("platform");
        assertThat(created.status()).as("%s", created).isEqualTo(200);
        long id = created.data().path("id").asLong();
        displayedPrice(CHARGE);
        String orderNo = pay();
        var sale = ledger(orderNo).itemWhere("recordType", "SALE");
        assertThat(sale.path("paidAmount").asInt()).isEqualTo(CHARGE);
        assertThat(sale.path("grossAmount").asInt()).isEqualTo(Journey.PRICE);
        assertThat(sale.path("platformExpense").asInt()).isEqualTo(DISCOUNT);
        assertThat(sale.path("feeAmount").asInt()).isEqualTo(Journey.PRICE * PARTNER_FEE_RATE / 100);
        assertThat(sale.path("promotionId").asLong()).isEqualTo(id);
        String sellerId = sale.path("sellerId").asText();

        String month = YearMonth.now().toString();
        Response closed = Stove.gateway.post("/api/v1/settlements/close?month=" + month,
                null, Journey.asAdmin());
        assertThat(closed.status()).as("%s", closed).isEqualTo(200);
        refund(orderNo);
        assertThat(ledger(orderNo).itemWhere("recordType", "REFUND")
                .path("adjustmentForMonth").asText()).isEqualTo(month);
        Response revised = Stove.gateway.post("/api/v1/settlements/close?month=" + month,
                null, Journey.asAdmin());
        assertThat(revised.status()).as("%s", revised).isEqualTo(200);
        Response reconciled = Stove.gateway.get("/api/v1/settlements/reconciliation?month=" + month,
                Journey.asAdmin());
        assertThat(reconciled.status()).as("%s", reconciled).isEqualTo(200);
        var seller = reconciled.itemWhere("sellerId", sellerId);
        assertThat(seller.isMissingNode()).isFalse();
        assertThat(seller.path("balanced").asBoolean()).isTrue();
        assertThat(seller.path("adjustmentCount").asInt()).isGreaterThanOrEqualTo(1);
        stop("platform", id);
        displayedPrice(Journey.PRICE);
    }
}
