package com.stove.catalog.core.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stove.common.core.error.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ProductTest {

    @Test
    @DisplayName("심의 승인 전에는 판매를 시작할 수 없다")
    void cannotOpenSaleBeforeReview() {
        Product product = Product.draft("GAME-001", "테스트 게임", 1001L, 10000L, "KRW");

        assertThatThrownBy(product::openSale).isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("심의 승인만으로는 판매를 시작할 수 없다")
    void reviewAloneCannotOpenSale() {
        Product product = Product.draft("GAME-001", "테스트 게임", 1001L, 10000L, "KRW");

        product.applyReviewApproval("15");
        assertThat(product.getStatus()).isEqualTo(ProductStatus.APPROVED);

        assertThatThrownBy(product::openSale).isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("공개 릴리스가 반영되면 구매 가능하다")
    void releaseMakesProductPurchasable() {
        Product product = Product.fromRelease(1L, "GAME-001", "테스트 게임", 1001L,
                10000L, "KRW", "15", 10L, 20L, 1L);

        assertThat(product.getStatus()).isEqualTo(ProductStatus.ON_SALE);
        product.requirePurchasable();
    }

    @Test
    @DisplayName("판매 중지 상품은 주문 단계에서 걸러진다")
    void suspendedProductIsNotPurchasable() {
        Product product = Product.fromRelease(1L, "GAME-001", "테스트 게임", 1001L,
                10000L, "KRW", "15", 10L, 20L, 1L);
        product.suspend();

        assertThatThrownBy(product::requirePurchasable).isInstanceOf(BusinessException.class);
    }
}
