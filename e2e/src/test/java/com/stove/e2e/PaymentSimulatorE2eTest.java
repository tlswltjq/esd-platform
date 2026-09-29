package com.stove.e2e;

import static com.stove.e2e.Journey.MEMBER;
import static com.stove.e2e.Journey.OTHER_MEMBER;
import static com.stove.e2e.Journey.PRICE;
import static org.assertj.core.api.Assertions.assertThat;

import com.stove.common.core.error.ErrorCode;
import com.stove.e2e.E2eClient.Response;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

/** 데모 PG 제어로 실제 구매·지급·환불 이벤트 흐름을 통과한다. */
@Order(5)
@DisplayName("데모 결제 — 승인·거절·시간 초과·환불")
class PaymentSimulatorE2eTest {

    private static String approvedOrder;
    private static String approvedPgTx;

    private static String newOrder() {
        Response product = Stove.gateway.get("/api/v1/products/by-code/" + Journey.PRODUCT_CODE);
        assertThat(product.status()).as("%s", product).isEqualTo(200);
        Response response = Stove.gateway.post("/api/v1/orders", Map.of(
                "items", List.of(Map.of("productId", Journey.productId(), "quantity", 1)),
                "expectedAmount", PRICE), Journey.asMember(MEMBER));
        assertThat(response.status()).as("%s", response).isEqualTo(200);
        assertThat(response.data().path("currency").asText()).isEqualTo("KRW");
        assertThat(response.data().path("totalAmount").asInt()).isEqualTo(PRICE);
        assertThat(response.data().path("retryable").asBoolean()).isTrue();
        assertThat(response.data().path("lines").size()).isEqualTo(1);
        assertThat(response.data().path("lines").get(0).path("unitPrice").asInt()).isEqualTo(PRICE);
        assertThat(response.data().path("lines").get(0).path("sellerId").asLong())
                .isEqualTo(product.data().path("sellerId").asLong());
        String orderNo = response.data().path("orderNo").asText();
        Await.untilResponse("시뮬레이터 결제 대기", () -> payment(orderNo),
                r -> "READY".equals(r.data().path("status").asText()));
        return orderNo;
    }

    private static String prepare(String orderNo) {
        Response response = Stove.gateway.post("/api/v1/payments/" + orderNo + "/prepare",
                Map.of("method", "CARD"), Journey.asMember(MEMBER));
        assertThat(response.status()).as("%s", response).isEqualTo(200);
        assertThat(response.data().path("amount").asInt()).isEqualTo(PRICE);
        assertThat(response.data().path("redirectUrl").asText())
                .endsWith("/api/v1/payments/simulator/checkout/" + response.data().path("pgTxId").asText());
        return response.data().path("pgTxId").asText();
    }

    private static Response payment(String orderNo) {
        return Stove.gateway.get("/api/v1/payments/" + orderNo, Journey.asMember(MEMBER));
    }

    @Test
    @Order(1)
    void checkoutRequiresOwnerAndControlsRequireAdmin() {
        Response product = Stove.gateway.get("/api/v1/products/by-code/" + Journey.PRODUCT_CODE);
        assertThat(product.status()).as("%s", product).isEqualTo(200);
        assertThat(product.data().path("productKind").asText()).isEqualTo("BASIC");
        assertThat(product.data().path("currency").asText()).isEqualTo("KRW");
        approvedOrder = newOrder();
        approvedPgTx = prepare(approvedOrder);
        String checkout = "/api/v1/payments/simulator/checkout/" + approvedPgTx;
        assertThat(Stove.gateway.get(checkout).status()).isEqualTo(401);
        assertThat(Stove.gateway.get(checkout, Journey.asMember(OTHER_MEMBER)).status()).isEqualTo(403);
        Response owner = Stove.gateway.get(checkout, Journey.asMember(MEMBER));
        assertThat(owner.status()).as("%s", owner).isEqualTo(200);
        assertThat(owner.data().path("amount").asInt()).isEqualTo(PRICE);
        assertThat(owner.data().path("currency").asText()).isEqualTo("KRW");
        assertThat(Stove.gateway.post("/api/v1/payments/simulator/" + approvedOrder + "/approve",
                null, Journey.asMember(MEMBER)).status()).isEqualTo(403);
        assertThat(payment(approvedOrder).data().path("status").asText()).isEqualTo("PENDING");
    }

    @Test
    @Order(2)
    void approvalDeliversThenRefundRevokes() {
        String approve = "/api/v1/payments/simulator/" + approvedOrder + "/approve";
        Response response = Stove.gateway.post(approve, null, Journey.asAdmin());
        assertThat(response.status()).as("%s", response).isEqualTo(200);
        assertThat(response.data().path("status").asText()).isEqualTo("PAID");
        assertThat(Stove.gateway.post(approve, null, Journey.asAdmin()).status()).isEqualTo(200);

        Await.untilResponse("시뮬레이터 구매 라이브러리", () ->
                        Stove.gateway.get("/api/v1/library", Journey.asMember(MEMBER)),
                r -> !r.itemWhere("orderNo", approvedOrder).isMissingNode());
        Await.untilResponse("시뮬레이터 구매 다운로드", () ->
                        Stove.gateway.get("/api/v1/downloads/" + Journey.PRODUCT_CODE + "/ticket",
                                Journey.asMember(MEMBER)), r -> r.status() == 200);

        Response refund = Stove.gateway.post("/api/v1/payments/simulator/" + approvedOrder + "/refund",
                null, Journey.asAdmin());
        assertThat(refund.status()).as("%s", refund).isEqualTo(200);
        assertThat(refund.data().path("status").asText()).isEqualTo("CANCELED");
        assertThat(Stove.gateway.post("/api/v1/payments/simulator/" + approvedOrder + "/refund",
                null, Journey.asAdmin()).status()).isEqualTo(200);
        Await.untilResponse("시뮬레이터 환불 주문", () ->
                        Stove.gateway.get("/api/v1/orders/" + approvedOrder, Journey.asMember(MEMBER)),
                r -> "CANCELED".equals(r.data().path("status").asText()));
        Await.untilResponse("시뮬레이터 환불 라이선스 회수", () ->
                        Stove.gateway.get("/api/v1/library", Journey.asMember(MEMBER)),
                r -> r.status() == 200 && r.itemWhere("orderNo", approvedOrder).isMissingNode());
        Await.untilResponse("시뮬레이터 환불 다운로드 회수", () ->
                        Stove.gateway.get("/api/v1/downloads/" + Journey.PRODUCT_CODE + "/ticket",
                                Journey.asMember(MEMBER)), r -> r.status() == 403);
    }

    @Test
    @Order(3)
    void declineAndTimeoutHaveTerminalReasons() {
        String declined = newOrder();
        prepare(declined);
        Response rejection = Stove.gateway.post("/api/v1/payments/simulator/" + declined + "/decline",
                Map.of("reasonCode", "CARD_DECLINED", "reason", "카드 거절"), Journey.asAdmin());
        assertThat(rejection.status()).as("%s", rejection).isEqualTo(200);
        assertThat(rejection.data().path("status").asText()).isEqualTo("FAILED");
        assertThat(rejection.data().path("failReasonCode").asText()).isEqualTo("CARD_DECLINED");
        assertThat(rejection.data().path("retryable").asBoolean()).isFalse();
        Await.untilResponse("시뮬레이터 거절 주문", () ->
                        Stove.gateway.get("/api/v1/orders/" + declined, Journey.asMember(MEMBER)),
                r -> "FAILED".equals(r.data().path("status").asText()));
        Response late = Stove.gateway.post("/api/v1/payments/simulator/" + declined + "/approve",
                null, Journey.asAdmin());
        assertThat(late.status()).as("%s", late).isEqualTo(409);
        assertThat(late.errorCode()).isEqualTo(ErrorCode.CONFLICT.name());

        String timedOut = newOrder();
        prepare(timedOut);
        Response timeout = Stove.gateway.post("/api/v1/payments/simulator/" + timedOut + "/timeout",
                null, Journey.asAdmin());
        assertThat(timeout.status()).as("%s", timeout).isEqualTo(200);
        assertThat(timeout.data().path("status").asText()).isEqualTo("FAILED");
        assertThat(timeout.data().path("failReasonCode").asText()).isEqualTo("PG_TIMEOUT");
        Await.untilResponse("시뮬레이터 시간 초과 주문", () ->
                        Stove.gateway.get("/api/v1/orders/" + timedOut, Journey.asMember(MEMBER)),
                r -> "FAILED".equals(r.data().path("status").asText()));
    }
}
