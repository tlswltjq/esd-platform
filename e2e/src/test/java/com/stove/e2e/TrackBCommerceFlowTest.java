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

/**
 * 트랙 B — 주문 → 결제.
 *
 * <p>여덟 건 중 <b>셋이 거절을 기대한다.</b> 그 셋이 이 장의 무게중심이다 — 검증 게이트 4단계
 * (README 3절)가 실제로 서 있는지는 서비스 하나를 띄워서는 볼 수 없다. 클라이언트가 보낸 금액을
 * order 가 catalog 재계산과 대조하고, payment 가 사전등록 금액과 승인 금액을 대조하는 흐름이라
 * <b>둘 사이</b>가 검증 대상이기 때문이다.
 *
 * <p>상태코드만이 아니라 {@link ErrorCode} 까지 대조한다. "409 이긴 한데 다른 이유로 409" 를
 * 가르기 위해서다 — 셸은 상태코드만 봤고, 그래서 사전등록을 빠뜨려 '승인 불가 상태' 로 409 가 나도
 * 금액 대조가 동작한 것처럼 읽혔다.
 */
@Order(2)
@DisplayName("트랙 B — 주문 → 결제")
class TrackBCommerceFlowTest {

    private static Map<String, Object> orderRequest(int expectedAmount) {
        return Map.of(
                "items", List.of(Map.of("productId", Journey.productId(), "quantity", 1)),
                "expectedAmount", expectedAmount);
    }

    /** 게이트 1 — 클라이언트 금액을 신뢰하지 않는다. catalog 재계산과 다르면 주문이 만들어지지 않는다. */
    @Test
    @Order(1)
    @DisplayName("order: 금액을 위조한 주문을 거부한다 (PRICE_MISMATCH)")
    void rejectsForgedAmount() {
        Response response = Stove.gateway.post("/api/v1/orders", orderRequest(100), Journey.asMember(MEMBER));

        assertThat(response.status()).as("%s", response).isEqualTo(409);
        assertThat(response.errorCode()).isEqualTo(ErrorCode.PRICE_MISMATCH.name());
    }

    @Test
    @Order(2)
    @DisplayName("order: 주문을 생성하면 OrderCreated 가 나간다")
    void createsOrder() {
        Response response = Stove.gateway.post("/api/v1/orders", orderRequest(PRICE), Journey.asMember(MEMBER));

        assertThat(response.status()).as("%s", response).isEqualTo(200);
        assertThat(response.data().path("totalAmount").asInt()).isEqualTo(PRICE);
        assertThat(response.data().path("status").asText()).isEqualTo("CREATED");
        Journey.orderNo(response.data().path("orderNo").asText());
    }

    @Test
    @Order(3)
    @DisplayName("payment: 결제 대기를 만든다 (OrderCreated 관통)")
    void createsPendingPayment() {
        Await.untilResponse("payment 결제 대기 생성",
                () -> Stove.gateway.get("/api/v1/payments/" + Journey.orderNo(), Journey.asMember(MEMBER)),
                r -> "READY".equals(r.data().path("status").asText()));
    }

    /** 게이트 2 — 승인 전에 서버가 확정한 금액을 PG 에 먼저 등록한다. */
    @Test
    @Order(4)
    @DisplayName("payment: PG 에 사전등록한다")
    void preparesPayment() {
        Response response = Stove.gateway.post(
                "/api/v1/payments/%s/prepare".formatted(Journey.orderNo()), Map.of("method", "STOVE_CASH"),
                Journey.asMember(MEMBER));

        assertThat(response.status()).as("%s", response).isEqualTo(200);
        assertThat(response.data().path("amount").asInt())
                .as("사전등록 금액은 클라이언트가 아니라 서버가 정한다")
                .isEqualTo(PRICE);
        Journey.PG_TX = response.data().path("pgTxId").asText();
        assertThat(Journey.PG_TX).isNotBlank();
    }

    /**
     * 콜백은 {@code result} 로 승인/거절이 갈린다. 기본값이 없으므로 빠뜨리면 400 이다 —
     * <b>표현되지 않은 결과가 조용히 승인이 되는</b> 성질을 만들지 않으려는 의도적 선택이다.
     */
    @Test
    @Order(5)
    @DisplayName("payment: result 없는 콜백을 거부한다 (400)")
    void rejectsCallbackWithoutResult() {
        Response response = Stove.gateway.post("/api/v1/payments/callback", Map.of(
                "orderNo", Journey.orderNo(),
                "pgTxId", Journey.PG_TX,
                "paidAmount", PRICE,
                "idempotencyKey", Journey.idempotencyKey("NO-RESULT")));

        assertThat(response.status()).as("%s", response).isEqualTo(400);
        assertThat(response.errorCode()).isEqualTo(ErrorCode.INVALID_REQUEST.name());
    }

    /** 게이트 3 — 사전등록 금액과 다른 승인은 확정하지 않는다. */
    @Test
    @Order(6)
    @DisplayName("payment: 사전등록 금액과 다른 승인을 거부한다 (PAYMENT_AMOUNT_MISMATCH)")
    void rejectsAmountMismatch() {
        Response response = Stove.gateway.post("/api/v1/payments/callback", approval(1, "BAD-AMOUNT"));

        assertThat(response.status()).as("%s", response).isEqualTo(409);
        assertThat(response.errorCode()).isEqualTo(ErrorCode.PAYMENT_AMOUNT_MISMATCH.name());
    }

    /**
     * 이 한 번의 콜백이 트랙 C 의 기준점이다.
     *
     * <p>팬아웃이 여기서 시작하므로 <b>종단 지연의 시작 시각</b>도, <b>트레이스 판정의 traceId</b> 도
     * 같은 응답에서 잡아 둔다({@link Journey#paymentAccepted}). traceId 는 응답 헤더로 돌아온다 —
     * {@code common:web} 의 {@code TraceIdResponseFilter} 가 붙이고, 게이트웨이는 그대로 통과시킨다.
     */
    @Test
    @Order(7)
    @DisplayName("payment: 승인 콜백을 받으면 PaymentCompleted 가 나간다")
    void acceptsApproval() {
        Response response = Stove.gateway.post("/api/v1/payments/callback", approval(PRICE, "PAID"));

        assertThat(response.status()).as("%s", response).isEqualTo(200);

        String traceId = response.headers().getFirst("X-Correlation-Id");
        assertThat(traceId)
                .as("장애 문의에 이 값을 달라고 말할 창구가 없어진다 — TraceIdResponseFilter 를 본다")
                .isNotBlank();
        Journey.paymentAccepted(traceId);
    }

    @Test
    @Order(8)
    @DisplayName("payment: 거절된 콜백들 뒤에도 상태는 PAID 하나뿐이다")
    void settlesAsPaid() {
        Response response = Stove.gateway.get("/api/v1/payments/" + Journey.orderNo(), Journey.asMember(MEMBER));

        assertThat(response.data().path("status").asText())
                .as("거부된 콜백 둘은 상태를 건드리지 않았어야 한다 — %s", response)
                .isEqualTo("PAID");
        assertThat(response.data().path("amount").asInt()).isEqualTo(PRICE);
    }

    @Test
    @Order(9)
    @DisplayName("커머스 API는 인증 없이 호출할 수 없다")
    void commerceEndpointsRequireAuthentication() {
        assertThat(Stove.gateway.get("/api/v1/orders/" + Journey.orderNo()).status()).isEqualTo(401);
        assertThat(Stove.gateway.get("/api/v1/payments/" + Journey.orderNo()).status()).isEqualTo(401);
        assertThat(Stove.gateway.get("/api/v1/library").status()).isEqualTo(401);
        assertThat(Stove.gateway.get("/api/v1/downloads/" + Journey.PRODUCT_CODE + "/ticket").status())
                .isEqualTo(401);
        assertThat(Stove.gateway.get("/api/v1/settlements/orders/" + Journey.orderNo()).status())
                .isEqualTo(401);
    }

    @Test
    @Order(10)
    @DisplayName("심사자와 운영자 토큰에는 구매자 역할이 없다")
    void nonMemberRolesCannotUseCustomerApis() {
        assertThat(Stove.gateway.get("/api/v1/orders/" + Journey.orderNo(), Journey.asReviewer()).status())
                .isEqualTo(403);
        assertThat(Stove.gateway.get("/api/v1/payments/" + Journey.orderNo(), Journey.asAdmin()).status())
                .isEqualTo(403);
        assertThat(Stove.gateway.get("/api/v1/library", Journey.asReviewer()).status())
                .isEqualTo(403);
        assertThat(Stove.gateway.get("/api/v1/downloads/" + Journey.PRODUCT_CODE + "/ticket",
                Journey.asAdmin()).status()).isEqualTo(403);
    }

    @Test
    @Order(11)
    @DisplayName("다른 회원의 토큰에 X-Member-Id를 붙여도 주문과 결제에 접근할 수 없다")
    void forgedMemberHeaderCannotSelectAnotherOwner() {
        Map<String, String> forged = Map.of(
                "Authorization", Journey.asMember(OTHER_MEMBER).get("Authorization"),
                "X-Member-Id", Long.toString(MEMBER));
        assertThat(Stove.gateway.get("/api/v1/orders/" + Journey.orderNo(), forged).status()).isEqualTo(403);
        assertThat(Stove.gateway.get("/api/v1/payments/" + Journey.orderNo(), forged).status()).isEqualTo(403);
        assertThat(Stove.gateway.post("/api/v1/payments/" + Journey.orderNo() + "/cancel", null,
                forged).status()).isEqualTo(403);
        assertThat(Stove.gateway.get("/api/v1/settlements/orders/" + Journey.orderNo(),
                Journey.asMember(MEMBER)).status()).isEqualTo(403);
    }

    @Test
    @Order(13)
    @DisplayName("주문 본문의 memberId는 인증된 구매자를 바꾸지 못한다")
    void forgedOrderBodyCannotSelectAnotherOwner() {
        Response response = Stove.gateway.post("/api/v1/orders", Map.of(
                "memberId", OTHER_MEMBER,
                "items", List.of(Map.of("productId", Journey.productId(), "quantity", 1)),
                "expectedAmount", PRICE), Journey.asMember(MEMBER));
        assertThat(response.status()).as("%s", response).isEqualTo(200);
        assertThat(response.data().path("memberId").asLong()).isEqualTo(MEMBER);
    }

    @Test
    @Order(12)
    @DisplayName("서명 없는 PG 승인 요청은 결제 상태를 바꿀 수 없다")
    void unsignedCallbackIsRejected() {
        assertThat(Stove.gateway.postUnsigned("/api/v1/payments/callback", approval(PRICE, "UNSIGNED"))
                .status()).isEqualTo(401);
        assertThat(Stove.gateway.get("/api/v1/payments/" + Journey.orderNo(), Journey.asMember(MEMBER))
                .data().path("status").asText()).isEqualTo("PAID");
    }

    private static Map<String, Object> approval(int paidAmount, String idemSuffix) {
        return Map.of(
                "result", "APPROVED",
                "orderNo", Journey.orderNo(),
                "pgTxId", Journey.PG_TX,
                "paidAmount", paidAmount,
                "idempotencyKey", Journey.idempotencyKey(idemSuffix));
    }
}
