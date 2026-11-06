package com.stove.license.core.domain;

import static com.stove.license.LicenseFixtures.id;
import static com.stove.license.LicenseFixtures.paid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stove.common.web.error.BusinessException;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 결정 93~97: 구매 근거는 보관함에서 시작하고, 등록·정지·복구·환불 완료가 구매 근거 단위로 움직인다. */
class PurchaseStorageTest {
    static final Instant AT = Instant.parse("2026-11-06T01:00:00.123456Z");

    @Test
    @DisplayName("결제가 끝나면 구매 근거는 보관함에 들어가고 보유권은 아직 없다")
    void paidPurchaseStartsInStorage() {
        License purchase = License.grant(paid());

        assertThat(purchase.getStatus()).isEqualTo(PurchaseStatus.STORED);
    }

    @Test
    @DisplayName("쓸 수 있는 보유권이 없으면 등록해 새 보유권을 만든다")
    void registrationCreatesActiveLicense() {
        License purchase = License.grant(paid());

        LicenseRegistration registration = purchase.register(false, AT);

        assertThat(purchase.getStatus()).isEqualTo(PurchaseStatus.REGISTERED);
        assertThat(registration.getStatus()).isEqualTo(LicenseStatus.ACTIVE);
        assertThat(registration.getLicenseId()).isEqualTo(purchase.getLicenseId());
        assertThat(registration.getOrderNo()).isEqualTo(purchase.getOrderNo());
        assertThat(registration.getRegistrationId()).isNotEqualTo(purchase.getLicenseId());
        assertThat(registration.getRegisteredAt()).isEqualTo(AT);
    }

    @Test
    @DisplayName("이미 쓸 수 있는 보유권이 있으면 등록을 거절하고 보관함에 남긴다")
    void alreadyOwnedProductStaysInStorage() {
        License purchase = License.grant(paid());

        assertThatThrownBy(() -> purchase.register(true, AT)).isInstanceOf(BusinessException.class)
                .hasMessageContaining("이미 보유");
        assertThat(purchase.getStatus()).isEqualTo(PurchaseStatus.STORED);
    }

    @Test
    @DisplayName("환불로 정지된 보관함 항목은 등록할 수 없다")
    void suspendedStoredPurchaseCannotBeRegistered() {
        License purchase = License.grant(paid());
        purchase.suspend(id(), null);

        assertThatThrownBy(() -> purchase.register(false, AT)).isInstanceOf(BusinessException.class);
        assertThat(purchase.getStatus()).isEqualTo(PurchaseStatus.SUSPENDED);
    }

    @Test
    @DisplayName("보관함 항목의 정지가 복구되면 보관함으로 돌아간다")
    void storedSuspensionRestoresToStorage() {
        License purchase = License.grant(paid());
        String suspension = id();
        purchase.suspend(suspension, null);
        assertThat(purchase.getSuspendedFrom()).isEqualTo(PurchaseStatus.STORED);

        boolean returned = purchase.restore(suspension, null, false, AT);

        assertThat(purchase.getStatus()).isEqualTo(PurchaseStatus.STORED);
        assertThat(returned).isFalse();
        assertThat(purchase.getSuspensionRequestEventId()).isNull();
    }

    @Test
    @DisplayName("등록된 구매분을 정지하면 보유권도 정지되고, 다른 보유권이 없으면 그대로 복구된다")
    void registeredSuspensionRestoresLicense() {
        License purchase = License.grant(paid());
        LicenseRegistration registration = purchase.register(false, AT);
        String suspension = id();

        purchase.suspend(suspension, registration);
        assertThat(registration.getStatus()).isEqualTo(LicenseStatus.SUSPENDED);

        boolean returned = purchase.restore(suspension, registration, false, AT);
        assertThat(returned).isFalse();
        assertThat(purchase.getStatus()).isEqualTo(PurchaseStatus.REGISTERED);
        assertThat(registration.getStatus()).isEqualTo(LicenseStatus.ACTIVE);
    }

    @Test
    @DisplayName("복구할 때 새로 산 구매분이 이미 쓰이고 있으면 옛 구매분은 보관함으로 돌아가고 옛 보유권은 끝난다")
    void restorationWithAnotherActiveLicenseReturnsToStorage() {
        License purchase = License.grant(paid());
        LicenseRegistration registration = purchase.register(false, AT);
        String suspension = id();
        purchase.suspend(suspension, registration);

        boolean returned = purchase.restore(suspension, registration, true, AT);

        assertThat(returned).isTrue();
        assertThat(purchase.getStatus()).isEqualTo(PurchaseStatus.STORED);
        assertThat(registration.getStatus()).isEqualTo(LicenseStatus.ENDED);
        assertThat(registration.getEndedAt()).isEqualTo(AT);
    }

    @Test
    @DisplayName("환불 완료는 구매 근거와 보유권을 끝내고, 이후 등록·복구를 거절하며 재전달은 바꾸지 않는다")
    void refundCompletionEndsPurchase() {
        License purchase = License.grant(paid());
        LicenseRegistration registration = purchase.register(false, AT);
        String suspension = id();
        purchase.suspend(suspension, registration);

        assertThat(purchase.refund(suspension, registration, AT)).isTrue();
        assertThat(purchase.getStatus()).isEqualTo(PurchaseStatus.REFUNDED);
        assertThat(registration.getStatus()).isEqualTo(LicenseStatus.ENDED);

        assertThat(purchase.refund(suspension, registration, AT)).isFalse();
        assertThatThrownBy(() -> purchase.restore(suspension, registration, false, AT)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> purchase.register(false, AT)).isInstanceOf(BusinessException.class);
        assertThat(purchase.getStatus()).isEqualTo(PurchaseStatus.REFUNDED);
    }

    @Test
    @DisplayName("다른 정지 요청의 환불 완료는 거절한다")
    void refundOfAnotherSuspensionIsRejected() {
        License purchase = License.grant(paid());
        purchase.suspend(id(), null);

        assertThatThrownBy(() -> purchase.refund(id(), null, AT)).isInstanceOf(BusinessException.class);
        assertThat(purchase.getStatus()).isEqualTo(PurchaseStatus.SUSPENDED);
    }
}
