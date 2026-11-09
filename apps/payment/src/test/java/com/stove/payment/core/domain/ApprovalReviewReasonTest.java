package com.stove.payment.core.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The bounded gauge and the ADMIN reason filter know every reason the approval worker can hold with (decisions 114·115·124·126). */
class ApprovalReviewReasonTest {
    @Test void theOrderExpiryPendingHoldIsAKnownBoundedReason() {
        assertThat(ApprovalReviewReason.KNOWN).contains("PG_PENDING_AT_ORDER_EXPIRY");
        assertThat(ApprovalReviewReason.ALL).containsAll(ApprovalReviewReason.KNOWN).contains("OTHER").doesNotHaveDuplicates();
        assertThat(ApprovalReviewReason.ALL).hasSize(ApprovalReviewReason.KNOWN.size() + 1);
    }
    @Test void theConfirmWindowHoldIsAKnownBoundedReason() {
        assertThat(ApprovalReviewReason.KNOWN).contains(PaymentApproval.CONFIRM_WINDOW_EXPIRED);
    }
}
