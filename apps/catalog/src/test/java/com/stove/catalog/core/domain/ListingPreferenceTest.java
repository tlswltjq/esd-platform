package com.stove.catalog.core.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ListingPreferenceTest {
    @Test void automaticListingFollowsPurchasabilityWhileExplicitChoicesAreIndependent() {
        assertThat(ListingPreference.AUTO.isListed("ON_SALE")).isTrue();
        assertThat(ListingPreference.AUTO.isListed("STOPPED")).isFalse();
        assertThat(ListingPreference.AUTO.isListed("BLOCKED")).isFalse();
        assertThat(ListingPreference.LISTED.isListed("STOPPED")).isTrue();
        assertThat(ListingPreference.LISTED.isListed("BLOCKED")).isTrue();
        assertThat(ListingPreference.UNLISTED.isListed("ON_SALE")).isFalse();
    }

    @Test void unknownSalesStatesAreNotTreatedAsAValidListingDecision() {
        assertThatThrownBy(() -> ListingPreference.AUTO.isListed("UNKNOWN")).isInstanceOf(IllegalArgumentException.class);
    }
}
