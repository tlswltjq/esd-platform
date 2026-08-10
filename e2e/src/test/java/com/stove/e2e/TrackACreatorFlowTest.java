package com.stove.e2e;

import static com.stove.e2e.Journey.PRICE;
import static com.stove.e2e.Journey.PRODUCT_CODE;
import static com.stove.e2e.Journey.SELLER;
import static org.assertj.core.api.Assertions.assertThat;

import com.stove.e2e.E2eClient.Response;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

/**
 * 트랙 A — 등록 → 심의 → 노출.
 *
 * <p>여기서 보는 것은 <b>이벤트 하나가 서비스 넷을 건너는가</b>다.
 * {@code GameRegistered} 가 review 를 깨우고, {@code ReviewApproved} 가 studio 로 되돌아오면서
 * catalog 에 상품 마스터를 만들고, {@code ProductChanged} 가 store 의 검색 색인에 닿는다.
 * 각 서비스 안쪽은 {@code integrationTest} 가 이미 본다 — 이 층은 <b>사이</b>만 본다.
 *
 * <p>이 장이 실패하면 뒤의 세 장이 전부 실패한다. 건너뛰는 것이 아니라 실패다({@link Journey} 참고).
 */
@Order(1)
@DisplayName("트랙 A — 등록 → 심의 → 노출")
class TrackACreatorFlowTest {

    @Test
    @Order(1)
    @DisplayName("studio: 프로젝트를 생성한다")
    void createsProject() {
        Response response = Stove.gateway.post("/api/v1/studio/games", Map.of(
                "productCode", PRODUCT_CODE,
                "title", "인수 시나리오 게임",
                "sellerId", SELLER,
                "price", PRICE,
                "selfRated", true));

        assertThat(response.status()).as("%s", response).isEqualTo(200);
        assertThat(response.data().path("gameId").isNumber()).as("%s", response).isTrue();
        Journey.gameId(response.data().path("gameId").asLong());
    }

    @Test
    @Order(2)
    @DisplayName("studio: 심의를 신청하면 GameRegistered 가 나간다")
    void submitsForReview() {
        Response response = Stove.gateway.post(
                "/api/v1/studio/games/%d/submit".formatted(Journey.gameId()), null, Journey.asSeller());

        assertThat(response.status()).as("%s", response).isEqualTo(200);
    }

    @Test
    @Order(3)
    @DisplayName("review: 자체등급분류를 자동 승인한다 (GameRegistered 관통)")
    void reviewAutoApproves() {
        Await.untilResponse("review 자동 승인",
                () -> Stove.gateway.get("/api/v1/reviews"),
                r -> "APPROVED".equals(r.itemWhere("productCode", PRODUCT_CODE).path("status").asText()));
    }

    @Test
    @Order(4)
    @DisplayName("studio: 심의 결과가 역전파된다 (ReviewApproved 관통)")
    void studioReflectsApproval() {
        Await.untilResponse("studio 상태 역전파 APPROVED",
                () -> Stove.gateway.get("/api/v1/studio/games", Journey.asSeller()),
                r -> "APPROVED".equals(r.itemWhere("productCode", PRODUCT_CODE).path("status").asText()));
    }

    /**
     * 목록({@code GET /products})은 {@code getOnSaleProducts()} 라 판매 시작 전에는 뜨지 않는다.
     * 그래서 {@code by-code} 로 직접 집는다 — 예전 셸은 id 1~12 를 훑다가
     * <b>정해진 상한을 넘는 순간 조용히 못 찾는</b> 구조였고, 실측 시점에 이미 10/12 였다.
     */
    @Test
    @Order(5)
    @DisplayName("catalog: 상품 마스터를 만든다 (ReviewApproved 관통)")
    void catalogCreatesProduct() {
        Await.untilResponse("catalog 상품 마스터 생성",
                () -> Stove.gateway.get("/api/v1/products/by-code/" + PRODUCT_CODE),
                r -> r.status() == 200 && PRODUCT_CODE.equals(r.data().path("productCode").asText()));

        Response response = Stove.gateway.get("/api/v1/products/by-code/" + PRODUCT_CODE);
        assertThat(response.data().path("price").asInt()).as("%s", response).isEqualTo(PRICE);
        Journey.productId(response.data().path("productId").asLong());
    }

    @Test
    @Order(6)
    @DisplayName("catalog: 판매를 시작하면 ProductChanged 가 나간다")
    void opensSale() {
        // 게이트웨이의 catalog 라우트는 GET 전용이다. 운영 호출이라 밖에서 닿지 않는 것이 정상이고,
        // 그래서 이 한 건만 catalog 를 직접 부른다(Stove 클래스 주석).
        Response response = Stove.catalog.post(
                "/api/v1/products/%d/sale-open".formatted(Journey.productId()), null);

        assertThat(response.status()).as("%s", response).isEqualTo(200);
    }

    /**
     * 목록은 {@code @PageableDefault(size = 20)} 이다. 정렬을 주지 않고 첫 페이지에서 찾으면
     * <b>ON_SALE 상품이 20개를 넘는 순간 조용히 못 찾게 된다</b> — 스택과 볼륨이 재사용되므로
     * 실행할 때마다 한 건씩 쌓이고, 어느 날 갑자기 빨개진다.
     *
     * <p>셸이 상품 마스터를 id 1~12 로 훑다가 {@code by-code} 로 바꾼 것과 <b>정확히 같은 함정</b>이고,
     * 그때 고치지 않고 남아 있던 자리다. 실제로 옮기고 나서 22번째 상품에서 터졌다.
     * 최신순으로 집으면 개수와 무관해진다.
     */
    @Test
    @Order(7)
    @DisplayName("catalog: ON_SALE 목록에 뜬다")
    void appearsInOnSaleList() {
        Await.untilResponse("catalog ON_SALE 목록 노출",
                () -> Stove.gateway.get("/api/v1/products?sort=id,desc"),
                r -> !r.itemWhere("productCode", PRODUCT_CODE).isMissingNode());

        // 목록에 떴다는 것과 상태가 바뀌었다는 것은 다르다. 후자를 직접 본다.
        Response detail = Stove.gateway.get("/api/v1/products/by-code/" + PRODUCT_CODE);
        assertThat(detail.data().path("status").asText()).as("%s", detail).isEqualTo("ON_SALE");
    }

    @Test
    @Order(8)
    @DisplayName("store: 검색 색인에 반영된다 (ProductChanged 관통)")
    void appearsInSearchIndex() {
        Await.untilResponse("store 검색 색인 반영",
                () -> Stove.gateway.get("/api/v1/storefront/products?q=인수"),
                r -> !r.itemWhere("productCode", PRODUCT_CODE).isMissingNode());
    }

    /**
     * 빌드 등록은 심의와 독립이다. 트랙 C 의 다운로드 티켓이 이 매니페스트를 요구하므로
     * ({@code issueTicket} 은 ProductRef 와 PatchManifest 를 둘 다 찾는다) 여기서 올려 둔다.
     */
    @Test
    @Order(9)
    @DisplayName("studio: 빌드를 등록하면 BuildUploaded 가 나간다")
    void uploadsBuild() {
        Response response = Stove.gateway.post(
                "/api/v1/studio/games/%d/builds".formatted(Journey.gameId()),
                Map.of("version", "1.0.0", "fileSize", 1_073_741_824L, "checksum", "a1b2c3"),
                Journey.asSeller());

        assertThat(response.status()).as("%s", response).isEqualTo(200);
    }

    @Test
    @Order(10)
    @DisplayName("download: 패치 매니페스트가 등록된다 (BuildUploaded 관통)")
    void registersPatchManifest() {
        Await.untilResponse("download 패치 매니페스트 등록",
                () -> Stove.gateway.get("/api/v1/downloads/%s/manifests".formatted(PRODUCT_CODE)),
                r -> !r.itemWhere("version", "1.0.0").isMissingNode());
    }
}
