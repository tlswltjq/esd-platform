package com.stove.license.core.domain;

import static com.stove.license.LicenseFixtures.id;
import static com.stove.license.LicenseFixtures.paid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class LicenseRestorationTest {
    @Test void restorationPreservesTheImmutablePurchaseAndReleasesOnlyTheNamedSuspension() {
        License license = License.grant(paid()); PaidOrder purchase = license.paidOrder(); String suspension = id(); license.suspend(suspension);
        license.restore(suspension);
        assertThat(license.getStatus()).isEqualTo(LicenseStatus.ACTIVE); assertThat(license.getSuspensionRequestEventId()).isNull();
        assertThat(license.paidOrder()).isEqualTo(purchase);
    }
    @Test void anotherSuspensionCannotBeRestoredAndAnActiveLicenseRequiresTheDurableRestorationHistoryForReplay() {
        License license = License.grant(paid()); license.suspend(id());
        assertThatThrownBy(() -> license.restore(id())).isInstanceOf(RuntimeException.class);
        assertThat(license.getStatus()).isEqualTo(LicenseStatus.SUSPENDED);
        License active = License.grant(paid()); assertThatThrownBy(() -> active.restore(id())).isInstanceOf(RuntimeException.class);
    }
}
