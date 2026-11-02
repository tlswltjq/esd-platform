package com.stove.catalog.core.domain;

import static com.stove.catalog.CatalogFixtures.event;
import static com.stove.catalog.CatalogFixtures.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stove.common.web.error.BusinessException;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class CatalogListingTest {
    private static final Instant NOW = Instant.parse("2026-11-02T00:00:00Z");
    @Test void defaultFollowsSalesAndExplicitChoiceSurvivesSalesAndPublication() {
        CatalogProduct p = CatalogProduct.publish(event(id(), 1).snapshot(), 1L, NOW);
        assertThat(p.getListingPreference()).isEqualTo(ListingPreference.AUTO);
        assertThat(p.isListed()).isTrue();
        p.changeSaleStatus("STOPPED", 1, NOW);
        assertThat(p.isListed()).isFalse();
        p.changeListingPreference(ListingPreference.LISTED, 2, NOW);
        assertThat(p.isListed()).isTrue();
        p.changePlatformRestriction(true, 3, NOW);
        assertThat(p.isListed()).isTrue();
        p.changePlatformListingHidden(true, 4, NOW);
        assertThat(p.isListed()).isFalse();
        p.replace(2L, 2, NOW);
        assertThat(p.getListingPreference()).isEqualTo(ListingPreference.LISTED);
        assertThat(p.isPlatformListingHidden()).isTrue();
        assertThat(p.isListed()).isFalse();
        p.changePlatformListingHidden(false, 6, NOW);
        assertThat(p.isListed()).isTrue();
    }
    @Test void liftingAdministratorHideDoesNotReplaceTheSellersChoice() {
        CatalogProduct p = CatalogProduct.publish(event(id(), 1).snapshot(), 1L, NOW);
        p.changeListingPreference(ListingPreference.UNLISTED, 1, NOW);
        p.changePlatformListingHidden(true, 2, NOW);
        p.changePlatformListingHidden(false, 3, NOW);
        assertThat(p.isListed()).isFalse();
        p.changeListingPreference(ListingPreference.AUTO, 4, NOW);
        assertThat(p.isListed()).isTrue();
        p.changePlatformRestriction(true, 5, NOW);
        assertThat(p.isListed()).isFalse();
        p.changePlatformRestriction(false, 6, NOW);
        assertThat(p.isListed()).isTrue();
        assertThat(p.changeListingPreference(ListingPreference.AUTO, 7, NOW)).isFalse();
        assertThatThrownBy(() -> p.changeListingPreference(ListingPreference.LISTED, 1, NOW)).isInstanceOf(BusinessException.class);
    }
}
