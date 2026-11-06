package com.stove.license.core.domain;

import static com.stove.license.LicenseFixtures.id;
import static com.stove.license.LicenseFixtures.paid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stove.common.web.error.BusinessException;
import org.junit.jupiter.api.Test;

class LicenseSuspensionTest {
    @Test
    void suspensionPreservesPurchaseEvidenceAndOnlyChangesAccessState() {
        License license = License.grant(paid());
        PaidOrder original = license.paidOrder();
        String requestId = id();

        license.suspend(requestId, null);

        assertThat(license.getStatus()).isEqualTo(PurchaseStatus.SUSPENDED);
        assertThat(license.getSuspensionRequestEventId()).isEqualTo(requestId);
        assertThat(license.paidOrder()).isEqualTo(original);
    }

    @Test
    void anotherRequestCannotReplaceAnActiveSuspension() {
        License license = License.grant(paid());
        String first = id();
        license.suspend(first, null);

        assertThatThrownBy(() -> license.suspend(id(), null)).isInstanceOf(BusinessException.class);
        assertThat(license.getSuspensionRequestEventId()).isEqualTo(first);
    }

    @Test
    void missingRequestEvidenceIsRejected() {
        License license = License.grant(paid());
        assertThatThrownBy(() -> license.suspend(null, null)).isInstanceOf(BusinessException.class);
        assertThat(license.getStatus()).isEqualTo(PurchaseStatus.STORED);
    }
}
