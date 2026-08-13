package com.stove.catalog.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.stove.catalog.core.domain.Product;
import com.stove.catalog.core.domain.ProductRepository;
import com.stove.common.testcontainers.InfraContainers;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 목록의 {@code sort} 파라미터 계약. [D-024]
 *
 * <p><b>계약</b> — 응답에 보이는 이름({@code productId})으로 정렬할 수 있고,
 * 그 밖의 이름은 {@code INVALID_REQUEST} 400 이다. 엔티티 필드명은 계약이 아니다.
 *
 * <p><b>고치기 전</b>에는 {@code productId} 와 모르는 이름이 둘 다 <b>500</b> 이었고,
 * 응답 어디에도 없는 엔티티 필드명 {@code id} 만 200 이었다. Spring Data 가 요청 문자열을
 * 엔티티 속성으로 그대로 해석하다 {@code PropertyReferenceException} 을 던지고,
 * 그 예외가 {@code GlobalExceptionHandler} 의 마지막 분기로 흘렀다.
 * 게이트웨이의 {@code catalog-public} 라우트가 이 경로를 인증 없이 열어 두므로
 * 누구나 5xx 를 만들 수 있었다(D-015·D-020 과 같은 부류).
 *
 * <p><b>왜 통합 소스셋인가</b> — 이 결함은 저장소가 실제로 쿼리를 만들 때 터진다.
 * 서비스를 {@code mock()} 으로 둔 {@link ProductControllerTest} 같은 standalone 구성에서는
 * 아무것도 재현되지 않는다. D-020 이 남긴 교훈 그대로다. 게다가 {@code src/test} 에는
 * {@code InfraContainers} 가 클래스패스에 없다 — 경계를 클래스패스가 지킨다.
 *
 * <p>컨텍스트를 {@code ProductLookupTest} 와 공유하지 못하고 따로 띄운다.
 * 서블릿 스택을 태워야 하는 유일한 통합 테스트라 {@code @AutoConfigureMockMvc} 가 필요하고,
 * 그 순간 컨텍스트 키가 달라진다.
 *
 * <p><b>상태 코드만 보지 않는다</b> [D-025] — 200 과 "배열이다"만 단언하면
 * {@code ProductSort.apply} 가 정렬을 통째로 떨어뜨려도 전부 통과한다. 실제로 그런 상태였다.
 * 그래서 응답에서 {@code productId} 를 뽑아 <b>순서 자체</b>를 단언한다.
 * 컨테이너를 공유하는 다른 테스트가 상품을 남기더라도, 단조성 단언은 볼륨과 무관하게 성립한다 —
 * 특정 id 를 기대하지 않는 것이 그 조건이다.
 */
@SpringBootTest(properties = "stove.outbox.relay-enabled=false")
@AutoConfigureMockMvc
@Import({InfraContainers.MySql.class, InfraContainers.Kafka.class, InfraContainers.Redis.class})
class ProductSortTest {

    @Autowired
    MockMvc mockMvc;
    @Autowired
    ProductRepository productRepository;

    @BeforeEach
    void onSaleProductsExist() {
        // 정렬이 실제로 실행되도록 목록에 상품이 있어야 한다. 빈 결과는 정렬 없이도 200 이다.
        // 값을 같게 둔다 — 동값이라야 꼬리표(id)가 하는 일이 순서에 드러난다.
        for (int i = 0; i < 3; i++) {
            Product product = Product.draft(
                    "GAME-" + UUID.randomUUID(), "게임 " + UUID.randomUUID(), 1001L, 18_000L, "KRW");
            product.applyReviewApproval("ALL");
            product.openSale();
            productRepository.save(product);
        }
    }

    @Test
    @DisplayName("[D-024] 응답 DTO 의 이름으로 정렬할 수 있다 — productId")
    void sortsByTheNameTheResponseShows() throws Exception {
        // 목록 응답이 productId 라는 이름으로 값을 돌려주므로, 그 이름으로 정렬을 요청하는 것이
        // 가장 자연스러운 시도다. 그게 정확히 500 이던 자리다.
        String body = mockMvc.perform(get("/api/v1/products").param("sort", "productId,desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray())
                .andReturn().getResponse().getContentAsString();

        // 요청한 정렬이 실제로 쿼리에 실렸는지는 순서로만 확인된다.
        assertThat(rowsIn(body)).isSortedAccordingTo(
                Comparator.comparingLong(Row::productId).reversed());
    }

    @Test
    @DisplayName("[D-025] 같은 값이 여럿이어도 순서가 정해진다 — price 정렬의 꼬리표")
    void tiesAreBrokenDeterministically() throws Exception {
        // price 는 유일하지 않다. 꼬리표가 없으면 동값 구간의 순서를 DB 가 그때그때 정하고,
        // 페이지를 넘기는 클라이언트가 같은 상품을 두 번 받거나 한 번도 못 받는다.
        String body = mockMvc.perform(get("/api/v1/products").param("sort", "price,asc"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // 가격이 다른 상품을 다른 테스트가 남겼을 수 있으므로 id 만 보지 않는다 —
        // 요청한 순서(price asc)와 꼬리표(id desc)를 합친 순서로 단언한다.
        assertThat(rowsIn(body)).isSortedAccordingTo(
                Comparator.comparingLong(Row::price)
                        .thenComparing(Comparator.comparingLong(Row::productId).reversed()));
    }

    @Test
    @DisplayName("[D-024] 모르는 정렬 속성은 INVALID_REQUEST 400 이다")
    void unknownSortPropertyIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/products").param("sort", "unknownField,asc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("[D-024] 엔티티 필드명은 계약이 아니다 — id 도 400 이다")
    void entityFieldNameIsNotPartOfTheContract() throws Exception {
        // 지금 이것만 200 인 이유는 설계가 아니라 부작용이다. 커밋된 OpenAPI 스냅샷은
        // sort 를 string[] 로만 적어 두고 유효한 키를 약속한 적이 없다.
        // 허용 목록을 두는 순간 여기 적히지 않은 이름은 전부 400 이 되어야 한다.
        mockMvc.perform(get("/api/v1/products").param("sort", "id,desc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("[D-024] 정렬을 주지 않아도 200 이고, [D-025] 기본 순서는 최신순이다")
    void noSortStillWorksAndIsOrdered() throws Exception {
        // 앞쪽 — 가드가 한 칸 넘치게 조여져 무정렬까지 막는 것을 여기서 막는다.
        String body = mockMvc.perform(get("/api/v1/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn().getResponse().getContentAsString();

        // 뒤쪽 — 정렬을 주지 않은 요청이 가장 흔하고, 그 요청이 ORDER BY 없이 페이징되고 있었다.
        // e2e 가 목록에서 상품을 찾으려고 sort 를 명시했던 것도 같은 이유다.
        assertThat(rowsIn(body)).isSortedAccordingTo(
                Comparator.comparingLong(Row::productId).reversed());
    }

    /** 순서를 단언하기 위해 필요한 만큼만 뽑은 응답 한 줄. */
    private record Row(long productId, long price) {
    }

    /**
     * 응답 본문에서 순서 판정에 쓸 값을 뽑는다.
     *
     * <p>특정 id 를 기대하지 않는다 — 컨테이너를 공유하는 다른 테스트가 남긴 상품이
     * 같은 목록에 섞이므로, 볼륨과 무관하게 성립하는 것은 <b>단조성</b>뿐이다.
     */
    private static List<Row> rowsIn(String body) {
        List<Number> productIds = JsonPath.read(body, "$.data[*].productId");
        List<Number> prices = JsonPath.read(body, "$.data[*].price");
        return IntStream.range(0, productIds.size())
                .mapToObj(i -> new Row(productIds.get(i).longValue(), prices.get(i).longValue()))
                .toList();
    }
}
