package com.stove.catalog.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stove.catalog.core.domain.Product;
import com.stove.catalog.core.domain.ProductRepository;
import com.stove.catalog.core.domain.ProductStatus;
import com.stove.catalog.core.domain.ProductView;
import com.stove.common.core.error.BusinessException;
import com.stove.common.core.error.ErrorCode;
import com.stove.common.testcontainers.InfraContainers;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * {@code productCode} → 상품 조회.
 *
 * <p>서비스 사이에서 상품을 가리키는 값은 내부 id 가 아니라 {@code productCode} 다.
 * 그 코드만 아는 쪽이 상품에 도달할 수 있는지가 여기서 정해진다.
 *
 * <p>어노테이션을 {@link ProductQuoteTest} 와 같게 두었다 — 스프링 컨텍스트가 재사용되므로
 * 클래스를 나누는 비용이 사실상 없다.
 */
@SpringBootTest(properties = "stove.outbox.relay-enabled=false")
@Import({InfraContainers.MySql.class, InfraContainers.Kafka.class, InfraContainers.Redis.class})
class ProductLookupTest {

    @Autowired
    ProductQueryService productQueryService;
    @Autowired
    ProductRepository productRepository;

    private Product draft() {
        return productRepository.save(Product.draft(
                "GAME-" + UUID.randomUUID(), "게임 " + UUID.randomUUID(), 1001L, 18_000L, "KRW"));
    }

    @Test
    @DisplayName("코드로 찾으면 내부 id 가 함께 돌아온다")
    void findsByCode() {
        Product saved = draft();

        ProductView found = productQueryService.getProductByCode(saved.getProductCode());

        assertThat(found.productId()).isEqualTo(saved.getId());
        assertThat(found.productCode()).isEqualTo(saved.getProductCode());
    }

    @Test
    @DisplayName("판매 시작 전 상품도 코드로 찾힌다")
    void findsProductNotYetOnSale() {
        // 이 성질이 이 조회의 존재 이유다. 목록(GET /products)은 ON_SALE 만 돌려주므로
        // 심의 승인 직후 — 아직 판매 시작 전 — 인 상품은 목록으로는 도달할 수 없다.
        // 그 구간에서 코드로 상품을 집을 수단이 없으면, 부르는 쪽은 id 를 추측하게 된다.
        Product saved = draft();

        ProductView found = productQueryService.getProductByCode(saved.getProductCode());

        assertThat(found.status()).isEqualTo(ProductStatus.DRAFT);
    }

    @Test
    @DisplayName("없는 코드는 PRODUCT_NOT_FOUND 다")
    void unknownCodeIsNotFound() {
        assertThatThrownBy(() -> productQueryService.getProductByCode("GAME-NO-SUCH-CODE"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.PRODUCT_NOT_FOUND);
    }
}
