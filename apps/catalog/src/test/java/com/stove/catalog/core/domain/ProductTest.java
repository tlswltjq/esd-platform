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
    @DisplayName("심의 승인 → 판매 시작 → 구매 가능")
    void reviewThenOpenSale() {
        Product product = Product.draft("GAME-001", "테스트 게임", 1001L, 10000L, "KRW");

        product.applyReviewApproval("15");
        assertThat(product.getStatus()).isEqualTo(ProductStatus.APPROVED);

        product.openSale();
        assertThat(product.getStatus()).isEqualTo(ProductStatus.ON_SALE);
        product.requirePurchasable();
    }

    @Test
    @DisplayName("판매 중지 상품은 주문 단계에서 걸러진다")
    void suspendedProductIsNotPurchasable() {
        Product product = Product.draft("GAME-001", "테스트 게임", 1001L, 10000L, "KRW");
        product.applyReviewApproval("15");
        product.openSale();
        product.suspend();

        assertThatThrownBy(product::requirePurchasable).isInstanceOf(BusinessException.class);
    }
}
