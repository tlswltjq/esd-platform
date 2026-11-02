package com.stove.catalog.core.domain;

import static com.stove.catalog.CatalogFixtures.event;
import static com.stove.catalog.CatalogFixtures.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stove.common.web.error.BusinessException;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class CatalogDownloadRestrictionTest {
    private static final Instant NOW = Instant.parse("2026-11-02T00:00:00Z");

    @Test void restrictionIsIndependentAndAReleaseCannotRemoveIt() {
        CatalogProduct product = CatalogProduct.publish(event(id(), 1).snapshot(), 1L, NOW);
        assertThat(product.isDownloadUnavailable()).isFalse();
        assertThat(product.changeDownloadRestriction(true, 1, NOW)).isTrue();
        assertThat(product.getDownloadRestrictionVersion()).isEqualTo(2);
        assertThat(product.getCatalogVersion()).isEqualTo(1);
        assertThat(product.getSaleStatus()).isEqualTo("ON_SALE");
        product.downloadStorageApplied(NOW);
        product.replace(2L, 2, NOW.plusSeconds(1));
        assertThat(product.isDownloadRestricted()).isTrue();
        assertThat(product.getDownloadStorageStatus()).isEqualTo("PENDING");
        assertThat(product.isDownloadUnavailable()).isTrue();
    }

    @Test void releaseIsUnavailableUntilStorageUnblockIsConfirmedAndStaleCommandsFail() {
        CatalogProduct product = CatalogProduct.publish(event(id(), 1).snapshot(), 1L, NOW);
        product.changeDownloadRestriction(true, 1, NOW);
        product.downloadStorageFailed(NOW);
        assertThat(product.getDownloadStorageStatus()).isEqualTo("FAILED");
        assertThatThrownBy(() -> product.changeDownloadRestriction(false, 1, NOW)).isInstanceOf(BusinessException.class);
        product.changeDownloadRestriction(false, 2, NOW);
        assertThat(product.isDownloadUnavailable()).isTrue();
        product.downloadStorageApplied(NOW);
        assertThat(product.isDownloadUnavailable()).isFalse();
        assertThat(product.changeDownloadRestriction(false, 3, NOW)).isFalse();
    }

    @Test void commandsValidateTheirOwnValues() {
        assertThatThrownBy(() -> new DownloadRestrictionChange("game", null, 1, "reason"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DownloadRestrictionChange("game", true, 1, " "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DownloadRestrictionChange("game", true, 0, "reason"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
