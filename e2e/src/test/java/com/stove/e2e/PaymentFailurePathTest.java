package com.stove.e2e;

import static com.stove.e2e.Journey.MEMBER;
import static com.stove.e2e.Journey.PRICE;
import static org.assertj.core.api.Assertions.assertThat;

import com.stove.common.core.error.ErrorCode;
import com.stove.e2e.E2eClient.Response;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

/**
 * 3-B — 결제 실패 경로 (PG 승인 거절).
 *
 * <p>승인과 대칭인 경로다. 주문을 새로 만드는 이유는 {@code FAILED} 가 <b>종단 상태</b>이기 때문이다 —
 * 카드를 바꿔 다시 시도하려면 어차피 새 주문이어야 한다.
 *
 * <p>이 장의 핵심은 {@link #failsOrderOnDecline()} 이다. 예전에는 거기서 주문이
 * {@code CREATED} 에 <b>영구히</b> 머물렀다 — 결제는 끝났는데 주문만 모르는 상태였고,
 * 그 자리는 컨슈머가 이벤트를 받아야 드러나므로 서비스 하나를 띄워서는 볼 수 없다.
 */
@Order(4)
@DisplayName("3-B — 결제 실패 경로 (PG 승인 거절)")
class PaymentFailurePathTest {

    private static Map<String, Object> decline(String pgTxId) {
        return Map.of(
                "result", "DECLINED",
                "orderNo", Journey.failOrderNo(),
                "pgTxId", pgTxId,
                "reasonCode", "REJECT_CARD_COMPANY",
                "reason", "카드사 거절");
    }

    @Test
    @Order(1)
    @DisplayName("order: 실패 검증용 주문을 새로 만든다")
    void createsOrderForFailure() {
        Response response = Stove.gateway.post("/api/v1/orders", Map.of(
                "memberId", MEMBER,
                "items", List.of(Map.of("productId", Journey.productId(), "quantity", 1)),
                "expectedAmount", PRICE));

        assertThat(response.status()).as("%s", response).isEqualTo(200);
        Journey.failOrderNo(response.data().path("orderNo").asText());
    }

    @Test
    @Order(2)
    @DisplayName("payment: 결제 대기를 만든다 (OrderCreated 관통)")
    void createsPendingPayment() {
        Await.untilResponse("payment 결제 대기 생성(3-B)",
                () -> Stove.gateway.get("/api/v1/payments/" + Journey.failOrderNo()),
                r -> "READY".equals(r.data().path("status").asText()));
    }

    /**
     * 거절에는 멱등키가 없다 — 돈이 움직이지 않아 PG 가 만들 승인 거래 키가 없다.
     * 그래서 {@code pgTxId} 가 이 거절이 <b>어느 거래</b>의 것인지 가리키는 유일한 값이고,
     * 사전등록이 돌려준 값을 그대로 써야 한다.
     */
    @Test
    @Order(3)
    @DisplayName("payment: PG 에 사전등록하고 거래번호를 받는다")
    void preparesPayment() {
        Response response = Stove.gateway.post(
                "/api/v1/payments/%s/prepare".formatted(Journey.failOrderNo()), Map.of("method", "CARD"));

        assertThat(response.status()).as("%s", response).isEqualTo(200);
        assertThat(response.data().path("pgTxId").asText()).isNotBlank();
        Journey.failPgTxId(response.data().path("pgTxId").asText());
    }

    @Test
    @Order(4)
    @DisplayName("payment: 다른 거래의 거절 콜백을 거부한다 (PAYMENT_TX_MISMATCH)")
    void rejectsDeclineOfAnotherTransaction() {
        Response response = Stove.gateway.post(
                "/api/v1/payments/callback", decline("PG-NOPE-" + Journey.failOrderNo()));

        assertThat(response.status()).as("%s", response).isEqualTo(409);
        assertThat(response.errorCode()).isEqualTo(ErrorCode.PAYMENT_TX_MISMATCH.name());
    }

    @Test
    @Order(5)
    @DisplayName("payment: 거절 콜백을 받으면 PaymentFailed 가 나간다")
    void acceptsDecline() {
        Response response = Stove.gateway.post("/api/v1/payments/callback", decline(Journey.failPgTxId()));

        assertThat(response.status()).as("%s", response).isEqualTo(200);
    }

    @Test
    @Order(6)
    @DisplayName("payment: 상태가 FAILED 로 확정된다")
    void settlesAsFailed() {
        Response response = Stove.gateway.get("/api/v1/payments/" + Journey.failOrderNo());

        assertThat(response.data().path("status").asText()).as("%s", response).isEqualTo("FAILED");
    }

    @Test
    @Order(7)
    @DisplayName("order: 주문이 FAILED 로 끝난다 (PaymentFailed 관통)")
    void failsOrderOnDecline() {
        Await.untilResponse("order 주문 실패 종료 FAILED",
                () -> Stove.gateway.get("/api/v1/orders/" + Journey.failOrderNo(), Journey.asMember(MEMBER)),
                r -> "FAILED".equals(r.data().path("status").asText()));
    }

    /** 중복 거절은 종단 상태로 흡수한다 — 승인이 멱등키로 흡수하는 것과 같은 자리다. */
    @Test
    @Order(8)
    @DisplayName("payment: 중복 거절 콜백을 흡수한다")
    void absorbsDuplicateDecline() {
        Response response = Stove.gateway.post("/api/v1/payments/callback", decline(Journey.failPgTxId()));

        assertThat(response.status()).as("%s", response).isEqualTo(200);
    }

    /** 거절 뒤에 오는 승인은 엇갈린 콜백이다. <b>조용히 삼키면 사고가 관측되지 않는다.</b> */
    @Test
    @Order(9)
    @DisplayName("payment: 거절 뒤에 온 승인 콜백을 거부한다 (PAYMENT_ALREADY_PROCESSED)")
    void rejectsLateApproval() {
        Response response = Stove.gateway.post("/api/v1/payments/callback", Map.of(
                "result", "APPROVED",
                "orderNo", Journey.failOrderNo(),
                "pgTxId", Journey.failPgTxId(),
                "paidAmount", PRICE,
                "idempotencyKey", Journey.idempotencyKey("LATE")));

        assertThat(response.status()).as("%s", response).isEqualTo(409);
        assertThat(response.errorCode()).isEqualTo(ErrorCode.PAYMENT_ALREADY_PROCESSED.name());
    }
}
