package com.stove.e2e;

import static com.stove.e2e.Journey.MEMBER;
import static com.stove.e2e.Journey.NET;
import static com.stove.e2e.Journey.OTHER_MEMBER;
import static com.stove.e2e.Journey.PRICE;
import static com.stove.e2e.Journey.PRODUCT_CODE;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.stove.common.core.error.ErrorCode;
import com.stove.e2e.E2eClient.Response;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

/**
 * 트랙 C — 지급 → 다운로드 → 정산 → 환불.
 *
 * <p>{@code PaymentCompleted} 하나가 넷으로 갈라진다(license · order · settlement, 그리고 license 가
 * 낳은 {@code LicenseIssued} 로 download). <b>팬아웃이 가장 큰 경로</b>라 릴레이가 밀리면 여기가
 * 먼저 티가 난다.
 *
 * <p>뒷부분은 그 갈라짐이 <b>되감기는지</b>를 본다. 환불 이벤트 하나에 주문·라이선스·다운로드 권한·
 * 정산 원장이 각각 자기 방식으로 반응해야 하고, 넷 중 하나라도 빠지면 사용자에게는
 * "환불했는데 게임이 남아 있다" 로 보인다.
 */
@Order(3)
@DisplayName("트랙 C — 지급 → 다운로드 → 정산 → 환불")
class TrackCFulfillmentFlowTest {

    private static final String TICKET = "/api/v1/downloads/%s/ticket".formatted(PRODUCT_CODE);

    private static Response library(long memberId) {
        return Stove.gateway.get("/api/v1/library", Journey.asMember(memberId));
    }

    private static Response ledger() {
        return Stove.gateway.get("/api/v1/settlements/orders/" + Journey.orderNo());
    }

    @Test
    @Order(1)
    @DisplayName("license: 라이선스를 지급한다 (PaymentCompleted 관통)")
    void issuesLicense() {
        Await.untilResponse("license 라이선스 지급",
                () -> library(MEMBER),
                r -> !r.itemWhere("orderNo", Journey.orderNo()).isMissingNode());
    }

    @Test
    @Order(2)
    @DisplayName("order: 주문을 PAID 로 확정한다 (PaymentCompleted 관통)")
    void confirmsOrder() {
        Await.untilResponse("order 주문 확정 PAID",
                () -> Stove.gateway.get("/api/v1/orders/" + Journey.orderNo(), Journey.asMember(MEMBER)),
                r -> "PAID".equals(r.data().path("status").asText()));
    }

    /** download 는 license 를 동기 호출하지 않는다 — 이벤트로 받아둔 권한 사본으로 판정한다. */
    @Test
    @Order(3)
    @DisplayName("download: 다운로드 권한을 부여한다 (LicenseIssued 관통)")
    void grantsDownloadRight() {
        Await.untilResponse("download 권한 부여",
                () -> Stove.gateway.get(TICKET, Journey.asMember(MEMBER)),
                r -> r.status() == 200);
    }

    @Test
    @Order(4)
    @DisplayName("download: 티켓을 발급한다")
    void issuesTicket() {
        Response response = Stove.gateway.get(TICKET, Journey.asMember(MEMBER));

        assertThat(response.status()).as("%s", response).isEqualTo(200);
        assertThat(response.data().path("version").asText()).isEqualTo("1.0.0");
    }

    @Test
    @Order(5)
    @DisplayName("download: 티켓에 서명 URL 이 들어 있다")
    void ticketCarriesSignedUrl() {
        Response response = Stove.gateway.get(TICKET, Journey.asMember(MEMBER));

        assertThat(response.data().path("downloadUrl").asText())
                .as("서명 URL 이 없으면 티켓은 발급됐어도 받을 수가 없다 — %s", response)
                .isNotBlank();
    }

    @Test
    @Order(6)
    @DisplayName("download: 사지 않은 회원은 403 이다")
    void refusesNonOwner() {
        Response response = Stove.gateway.get(TICKET, Journey.asMember(OTHER_MEMBER));

        assertThat(response.status()).as("%s", response).isEqualTo(403);
        assertThat(response.errorCode()).isEqualTo(ErrorCode.FORBIDDEN.name());
    }

    @Test
    @Order(7)
    @DisplayName("settlement: 매출 원장을 적립한다 (PaymentCompleted 관통)")
    void recordsSale() {
        Await.untilResponse("settlement 매출 원장 적립",
                TrackCFulfillmentFlowTest::ledger,
                r -> !r.itemWhere("recordType", "SALE").isMissingNode());
    }

    /**
     * 셸은 이 자리를 정규식으로 봤다 — {@code "grossAmount":18000.*"netAmount":12600,}.
     * 필드 순서에 묶여 있었고, 깨져도 <b>무엇이</b> 틀렸는지 말하지 않았다.
     * 옮기면서 사라진 것이 이 한 줄이고, 그것이 모듈을 만든 이유의 절반이다.
     */
    @Test
    @Order(8)
    @DisplayName("settlement: 입점 판매는 수수료 30% 를 뗀다")
    void appliesPartnerFee() {
        Response response = ledger();
        JsonNode sale = response.itemWhere("recordType", "SALE");

        assertThat(response.status()).as("%s", response).isEqualTo(200);
        assertThat(sale.path("saleType").asText()).isEqualTo("PARTNER");
        assertThat(sale.path("grossAmount").asInt()).isEqualTo(PRICE);
        assertThat(sale.path("feeAmount").asInt()).isEqualTo(Journey.FEE);
        assertThat(sale.path("netAmount").asInt()).isEqualTo(NET);
    }

    /**
     * 멱등 — 같은 콜백이 다시 와도 이벤트를 재발행하지 않으므로 라이선스가 늘지 않는다.
     *
     * <p><b>'아무 일도 일어나지 않음' 은 기다릴 수 없다.</b> 재발행이 있었다면 도착했을 시간만큼만
     * 주고 센다 — 릴레이 폴링이 1초라 6초면 6주기다. 이 저니에서 고정 대기가 옳은 유일한 자리다.
     */
    @Test
    @Order(9)
    @DisplayName("payment · license: 중복 콜백을 흡수하고 지급은 1회로 남는다")
    void absorbsDuplicateCallback() throws InterruptedException {
        int before = library(MEMBER).data().size();

        Response response = Stove.gateway.post("/api/v1/payments/callback", Map.of(
                "result", "APPROVED",
                "orderNo", Journey.orderNo(),
                "pgTxId", Journey.PG_TX,
                "paidAmount", PRICE,
                "idempotencyKey", Journey.idempotencyKey("PAID")));
        assertThat(response.status()).as("%s", response).isEqualTo(200);

        Thread.sleep(6_000);

        assertThat(library(MEMBER).data().size())
                .as("중복 콜백이 이벤트를 재발행했다면 라이선스가 늘었을 것이다")
                .isEqualTo(before);
    }

    // ── 환불: 하나의 이벤트가 네 방향으로 되감긴다 ────────────────────

    @Test
    @Order(10)
    @DisplayName("payment: 환불하면 PaymentCancelled 가 나간다")
    void cancelsPayment() {
        Response response = Stove.gateway.post(
                "/api/v1/payments/%s/cancel?reason=E2E_REFUND".formatted(Journey.orderNo()), null);

        assertThat(response.status()).as("%s", response).isEqualTo(200);
    }

    @Test
    @Order(11)
    @DisplayName("order: 주문을 취소한다 (PaymentCancelled 관통)")
    void cancelsOrder() {
        Await.untilResponse("order 주문 취소",
                () -> Stove.gateway.get("/api/v1/orders/" + Journey.orderNo(), Journey.asMember(MEMBER)),
                r -> "CANCELED".equals(r.data().path("status").asText()));
    }

    /** 라이브러리는 ACTIVE 만 반환한다 — 회수되면 목록에서 사라지는 것이 정상이다. */
    @Test
    @Order(12)
    @DisplayName("license: 라이선스를 회수한다 (라이브러리에서 사라진다)")
    void revokesLicense() {
        Await.untilResponse("license 라이선스 회수",
                () -> library(MEMBER),
                // 응답 자체가 실패한 것을 '없어졌다' 로 읽지 않는다 — 봉투를 먼저 본다.
                r -> r.status() == 200 && r.itemWhere("orderNo", Journey.orderNo()).isMissingNode());
    }

    @Test
    @Order(13)
    @DisplayName("download: 권한을 회수한다 (LicenseRevoked 관통)")
    void revokesDownloadRight() {
        Await.untilResponse("download 권한 회수",
                () -> Stove.gateway.get(TICKET, Journey.asMember(MEMBER)),
                r -> r.status() == 403);
    }

    /** 환불 이벤트에는 항목 정보가 없다. settlement 는 자기 원장의 SALE 을 부호 반전해 상계한다. */
    @Test
    @Order(14)
    @DisplayName("settlement: 환불을 부호 반전으로 역산한다")
    void reversesLedger() {
        Await.untilResponse("settlement 환불 역산",
                TrackCFulfillmentFlowTest::ledger,
                r -> r.itemWhere("recordType", "REFUND").path("grossAmount").asInt() == -PRICE);
    }

    @Test
    @Order(15)
    @DisplayName("settlement: 상계 후 순액이 0 이다")
    void ledgerNetsToZero() {
        Response response = ledger();
        JsonNode sale = response.itemWhere("recordType", "SALE");
        JsonNode refund = response.itemWhere("recordType", "REFUND");

        assertThat(response.status()).as("%s", response).isEqualTo(200);
        assertThat(sale.path("netAmount").asInt()).isEqualTo(NET);
        assertThat(refund.path("netAmount").asInt()).isEqualTo(-NET);
        assertThat(sale.path("netAmount").asInt() + refund.path("netAmount").asInt())
                .as("환불된 주문이 정산 대상에 남으면 판매자에게 돈이 두 번 간다 — %s", response)
                .isZero();
    }
}
