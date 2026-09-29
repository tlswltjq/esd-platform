package com.stove.catalog.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stove.catalog.core.domain.Product;
import com.stove.catalog.core.domain.ProductRepository;
import com.stove.catalog.core.domain.Promotion;
import com.stove.catalog.core.domain.QuoteItem;
import com.stove.common.core.error.BusinessException;
import com.stove.common.testcontainers.InfraContainers;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
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
    void sellerAndPlatformPromotionsFreezeTheSameChargeAndRejectOverlaps() {
        Product product = products.save(Product.fromRelease(1L, "PROMO-" + UUID.randomUUID(),
                "Game", 1001L, 10_000, "KRW", "ALL", 1L, 1L, 1L));
        Instant start = Instant.now().minusSeconds(5);
        Instant end = Instant.now().plusSeconds(600);
        Promotion seller = promotions.create(product.getId(), 1001L, Promotion.Bearer.SELLER,
                2_000, start, end, "creator");

        assertThat(queries.quote(List.of(new QuoteItem(product.getId(), 2))).totalAmount())
                .isEqualTo(16_000);
        assertThat(queries.quote(List.of(new QuoteItem(product.getId(), 1))).lines().getFirst())
                .extracting(line -> line.promotionId()).isEqualTo(seller.getId());
        assertThatThrownBy(() -> promotions.create(product.getId(), null,
                Promotion.Bearer.PLATFORM, 2_000, start, end, "admin"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> promotions.stop(seller.getId(), 2002L,
                Promotion.Bearer.SELLER, "other"))
                .isInstanceOf(BusinessException.class);

        promotions.stop(seller.getId(), 1001L, Promotion.Bearer.SELLER, "creator");
        Promotion platform = promotions.create(product.getId(), null, Promotion.Bearer.PLATFORM,
                2_000, start, end, "admin");
        var line = queries.quote(List.of(new QuoteItem(product.getId(), 1))).lines().getFirst();
        assertThat(line.unitPrice()).isEqualTo(8_000);
        assertThat(line.listUnitPrice()).isEqualTo(10_000);
        assertThat(line.discountBearer()).isEqualTo("PLATFORM");
        assertThat(line.promotionId()).isEqualTo(platform.getId());
    }
}
