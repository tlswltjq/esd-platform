package com.stove.catalog.core.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stove.catalog.CatalogFixtures;
import com.stove.common.web.error.BusinessException;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class CatalogSaleStatusTest {
    private final Instant first = Instant.parse("2026-11-02T01:00:00Z");

    @Test void sellerIntentAndPlatformRestrictionHaveIndependentReleaseRights() {
        CatalogProduct product = CatalogProduct.publish(CatalogFixtures.event(1, 1).snapshot(), 10L, first);
        product.changePlatformRestriction(true, 1, first.plusSeconds(1));
        assertThat(product.getSaleStatus()).isEqualTo("BLOCKED");
        product.changeSaleStatus("STOPPED", 2, first.plusSeconds(2));
        product.changeSaleStatus("ON_SALE", 3, first.plusSeconds(3));
        assertThat(product.getSaleStatus()).isEqualTo("BLOCKED");
        product.changeSaleStatus("STOPPED", 4, first.plusSeconds(4));
        product.changePlatformRestriction(false, 5, first.plusSeconds(5));
        assertThat(product.getSaleStatus()).isEqualTo("STOPPED");
        assertThat(product.getSellerSaleStatus()).isEqualTo("STOPPED");
        assertThat(product.isPlatformRestricted()).isFalse();
    }

    @Test void stoppingSalesPreservesThePublishedReleaseAndUsesASeparateCatalogVersion() {
        CatalogProduct product = CatalogProduct.publish(CatalogFixtures.event(1, 1).snapshot(), 10L, first);
        assertThat(product.getSaleStatus()).isEqualTo("ON_SALE");
        assertThat(product.changeSaleStatus("STOPPED", 1, first.plusSeconds(1))).isTrue();
        assertThat(product.getSaleStatus()).isEqualTo("STOPPED");
        assertThat(product.getCatalogVersion()).isEqualTo(2);
        assertThat(product.getCurrentReleaseRequestId()).isEqualTo(10L);
        assertThat(product.getPublishedAt()).isEqualTo(first);
        assertThat(product.changeSaleStatus("STOPPED", 2, first.plusSeconds(2))).isFalse();
        assertThat(product.getCatalogVersion()).isEqualTo(2);
    }

    @Test void anOldStopCannotUndoAResumeAndPublishingDoesNotResetTheSelectedSaleState() {
        CatalogProduct product = CatalogProduct.publish(CatalogFixtures.event(1, 1).snapshot(), 10L, first);
        product.changeSaleStatus("STOPPED", 1, first.plusSeconds(1));
        product.replace(20L, 2, first.plusSeconds(2));
        assertThat(product.getSaleStatus()).isEqualTo("STOPPED");
        assertThat(product.getCatalogVersion()).isEqualTo(3);
        product.changeSaleStatus("ON_SALE", 3, first.plusSeconds(3));
        assertThatThrownBy(() -> product.changeSaleStatus("STOPPED", 1, first.plusSeconds(4)))
                .isInstanceOf(BusinessException.class);
        assertThat(product.getSaleStatus()).isEqualTo("ON_SALE");
        assertThat(product.getCatalogVersion()).isEqualTo(4);
    }
}
