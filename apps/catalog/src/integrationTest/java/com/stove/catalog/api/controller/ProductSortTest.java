package com.stove.catalog.api.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.stove.catalog.core.domain.Product;
import com.stove.catalog.core.domain.ProductRepository;
import com.stove.common.testcontainers.InfraContainers;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 목록의 {@code sort} 파라미터 계약. [D-024]
 *
 * <p><b>기대</b> — 응답에 보이는 이름({@code productId})으로 정렬할 수 있고,
 * 그 밖의 이름은 {@code INVALID_REQUEST} 400 이다.
 *
 * <p><b>실제</b> — {@code productId} 와 모르는 이름이 둘 다 <b>500</b> 이고,
 * 응답 어디에도 없는 엔티티 필드명 {@code id} 만 200 이다. Spring Data 가 요청 문자열을
 * 엔티티 속성으로 그대로 해석하다 {@code PropertyReferenceException} 을 던지고,
 * 그 예외가 {@code GlobalExceptionHandler} 의 마지막 분기로 흘러간다.
 *
 * <p><b>영향</b> — 게이트웨이의 {@code catalog-public} 라우트가 이 경로를 인증 없이 열어 둔다.
 * 누구나 5xx 를 만들 수 있고, 클라이언트 잘못이 서버 장애로 집계된다(D-015·D-020 과 같은 부류).
 *
 * <p><b>왜 통합 소스셋인가</b> — 이 결함은 저장소가 실제로 쿼리를 만들 때 터진다.
 * 서비스를 {@code mock()} 으로 둔 {@link ProductControllerTest} 같은 standalone 구성에서는
 * 아무것도 재현되지 않는다. D-020 이 남긴 교훈 그대로다. 게다가 {@code src/test} 에는
 * {@code InfraContainers} 가 클래스패스에 없다 — 경계를 클래스패스가 지킨다.
 *
 * <p>컨텍스트를 {@code ProductLookupTest} 와 공유하지 못하고 따로 띄운다.
 * 서블릿 스택을 태워야 하는 유일한 통합 테스트라 {@code @AutoConfigureMockMvc} 가 필요하고,
 * 그 순간 컨텍스트 키가 달라진다.
 */
@SpringBootTest(properties = "stove.outbox.relay-enabled=false")
@AutoConfigureMockMvc
@Import({InfraContainers.MySql.class, InfraContainers.Kafka.class, InfraContainers.Redis.class})
@Tag("known-defect")
class ProductSortTest {

    @Autowired
    MockMvc mockMvc;
    @Autowired
    ProductRepository productRepository;

    @BeforeEach
    void onSaleProductExists() {
        // 정렬이 실제로 실행되도록 목록에 한 건은 있어야 한다. 빈 결과는 정렬 없이도 200 이다.
        Product product = Product.draft(
                "GAME-" + UUID.randomUUID(), "게임 " + UUID.randomUUID(), 1001L, 18_000L, "KRW");
        product.applyReviewApproval("ALL");
        product.openSale();
        productRepository.save(product);
    }

    @Test
    @DisplayName("[D-024] 응답 DTO 의 이름으로 정렬할 수 있다 — productId")
    void sortsByTheNameTheResponseShows() throws Exception {
        // 목록 응답이 productId 라는 이름으로 값을 돌려주므로, 그 이름으로 정렬을 요청하는 것이
        // 가장 자연스러운 시도다. 그게 정확히 500 이던 자리다.
        mockMvc.perform(get("/api/v1/products").param("sort", "productId,desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray());
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
    @DisplayName("[D-024] 정렬을 주지 않으면 그대로 통과한다")
    void noSortStillWorks() throws Exception {
        // 가드가 한 칸 넘치게 조여져 무정렬까지 막는 것을 여기서 막는다.
        mockMvc.perform(get("/api/v1/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }
}
