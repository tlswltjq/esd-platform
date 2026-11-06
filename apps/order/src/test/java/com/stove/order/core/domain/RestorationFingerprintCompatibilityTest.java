package com.stove.order.core.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** PR #116 리뷰 R3: 업그레이드 전에 저장한 복구 지문이 같은 원본 이벤트의 재전달과 계속 맞아야 한다. */
class RestorationFingerprintCompatibilityTest {
    static final Instant AT = Instant.parse("2026-10-27T01:05:00.123456Z");
    final String refundId = UUID.randomUUID().toString();
    final String attemptId = UUID.randomUUID().toString();
    final String licenseId = UUID.randomUUID().toString();
    final String orderNo = UUID.randomUUID().toString();
    final String reviewId = UUID.randomUUID().toString();
    final String stopId = UUID.randomUUID().toString();

    @Test
    @DisplayName("보관함 반환이 아닌 복구 완료는 업그레이드 전 지문과 같고, 보관함 반환은 다른 지문이다")
    void restorationFingerprintKeepsTheLegacyFormatForFalse() {
        String legacy = RefundRestorationValues.fingerprint("restored-1", AT.toString(), "restore-1", refundId, attemptId, "failure-1",
                licenseId, orderNo, "buyer", "1", "PRODUCT-1", "payment-1", "suspend-1", "suspended-1");

        assertThat(restoration(false).fingerprint()).isEqualTo(legacy);
        assertThat(restoration(true).fingerprint()).isNotEqualTo(legacy);
    }

    @Test
    @DisplayName("승인 정정 복구도 보관함 반환이 아니면 업그레이드 전 지문과 같다")
    void approvalCorrectionFingerprintKeepsTheLegacyFormatForFalse() {
        String legacy = RefundRestorationValues.fingerprint("corrected-1", AT.toString(), "correction-1", refundId, reviewId, stopId,
                licenseId, orderNo, "buyer", "1", "PRODUCT-1", "payment-1", "suspend-1", "suspended-1");

        assertThat(correction(false).fingerprint()).isEqualTo(legacy);
        assertThat(correction(true).fingerprint()).isNotEqualTo(legacy);
    }

    private LicenseRestorationConfirmation restoration(boolean returned) {
        return new LicenseRestorationConfirmation("restored-1", AT, "restore-1", refundId, attemptId, "failure-1", licenseId, orderNo,
                "buyer", 1L, "PRODUCT-1", "payment-1", "suspend-1", "suspended-1", returned);
    }

    private ApprovalCorrectionConfirmation correction(boolean returned) {
        return new ApprovalCorrectionConfirmation("corrected-1", AT, "correction-1", refundId, reviewId, stopId, licenseId, orderNo,
                "buyer", 1L, "PRODUCT-1", "payment-1", "suspend-1", "suspended-1", returned);
    }
}
