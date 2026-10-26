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

        license.suspend(requestId);

        assertThat(license.getStatus()).isEqualTo(LicenseStatus.SUSPENDED);
        assertThat(license.getSuspensionRequestEventId()).isEqualTo(requestId);
        assertThat(license.paidOrder()).isEqualTo(original);
    }

    @Test
    void anotherRequestCannotReplaceAnActiveSuspension() {
        License license = License.grant(paid());
        String first = id();
        license.suspend(first);

        assertThatThrownBy(() -> license.suspend(id())).isInstanceOf(BusinessException.class);
        assertThat(license.getSuspensionRequestEventId()).isEqualTo(first);
    }

    @Test
    void missingRequestEvidenceIsRejected() {
        License license = License.grant(paid());
        assertThatThrownBy(() -> license.suspend(null)).isInstanceOf(BusinessException.class);
        assertThat(license.getStatus()).isEqualTo(LicenseStatus.ACTIVE);
    }
}
