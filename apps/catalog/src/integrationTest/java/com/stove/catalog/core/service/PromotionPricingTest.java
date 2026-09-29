package com.stove.catalog.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stove.catalog.core.domain.Product;
import com.stove.catalog.core.domain.ProductRepository;
import com.stove.catalog.core.domain.Promotion;
import com.stove.catalog.core.domain.QuoteItem;
import com.stove.common.core.error.BusinessException;
import com.stove.common.core.error.ErrorCode;
import com.stove.common.testcontainers.InfraContainers;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest(properties = "stove.outbox.relay-enabled=false")
@Import({InfraContainers.MySql.class, InfraContainers.Kafka.class, InfraContainers.Redis.class})
class PromotionPricingTest {
    @Autowired ProductRepository products;
    @Autowired PromotionService promotions;
    @Autowired ProductQueryService queries;

    @Test
    @DisplayName("판매자·플랫폼 할인은 정산 기준을 달리 고정하고 겹친 행사와 타인의 중단은 거부한다")
    void sellerAndPlatformPromotionsFreezeTheSameChargeAndRejectOverlaps() {
        Product product = products.save(Product.fromRelease(1L, "PROMO-" + UUID.randomUUID(),
                "Game", 1001L, 10_000, "KRW", "ALL", 1L, 1L, 1L));
        Instant start = Instant.now().minusSeconds(5);
        Instant end = Instant.now().plusSeconds(600);
        Promotion seller = promotions.create(product.getId(), 1001L, Promotion.Bearer.SELLER,
                2_000, start, end, "creator");

        var sellerQuote = queries.quote(List.of(new QuoteItem(product.getId(), 2)));
        assertThat(sellerQuote.totalAmount()).isEqualTo(16_000);
        var sellerLine = sellerQuote.lines().getFirst();
        assertThat(sellerLine.promotionId()).isEqualTo(seller.getId());
        assertThat(sellerLine.discountBearer()).isEqualTo("SELLER");
        assertThat(sellerLine.settlementBasis()).isEqualTo(16_000);
        assertThat(sellerLine.feeAmount()).isEqualTo(4_800);
        assertThat(sellerLine.sellerPayout()).isEqualTo(11_200);
        assertThatThrownBy(() -> promotions.create(product.getId(), null,
                Promotion.Bearer.PLATFORM, 2_000, start, end, "admin"))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.CONFLICT));
        assertThatThrownBy(() -> promotions.stop(seller.getId(), 2002L,
                Promotion.Bearer.SELLER, "other"))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));

        promotions.stop(seller.getId(), 1001L, Promotion.Bearer.SELLER, "creator");
        Promotion platform = promotions.create(product.getId(), null, Promotion.Bearer.PLATFORM,
                2_000, start, end, "admin");
        var line = queries.quote(List.of(new QuoteItem(product.getId(), 1))).lines().getFirst();
        assertThat(line.unitPrice()).isEqualTo(8_000);
        assertThat(line.listUnitPrice()).isEqualTo(10_000);
        assertThat(line.discountBearer()).isEqualTo("PLATFORM");
        assertThat(line.promotionId()).isEqualTo(platform.getId());
        assertThat(line.settlementBasis()).isEqualTo(10_000);
        assertThat(line.feeAmount()).isEqualTo(3_000);
        assertThat(line.sellerPayout()).isEqualTo(7_000);
    }

    @Test
    @DisplayName("할인액 0원·전액 할인과 끝난 기간·역전된 기간은 INVALID_REQUEST로 거부한다")
    void rejectsInvalidDiscountAndWindowBoundaries() {
        Product product = products.save(Product.fromRelease(1L, "PROMO-" + UUID.randomUUID(),
                "Game", 1001L, 10_000, "KRW", "ALL", 1L, 1L, 1L));
        Instant now = Instant.now();
        Instant start = now.plusSeconds(60);
        Instant end = now.plusSeconds(600);

        assertInvalidPromotion(product.getId(), 0, start, end);
        assertInvalidPromotion(product.getId(), 10_000, start, end);
        assertInvalidPromotion(product.getId(), 2_000, end, start);
        assertInvalidPromotion(product.getId(), 2_000, now.minusSeconds(600), now.minusSeconds(60));
    }

    private void assertInvalidPromotion(Long productId, long discount, Instant start, Instant end) {
        assertThatThrownBy(() -> promotions.create(productId, 1001L, Promotion.Bearer.SELLER,
                discount, start, end, "creator"))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
    }
}
