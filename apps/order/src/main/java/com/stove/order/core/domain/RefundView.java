package com.stove.order.core.domain;

import java.time.Instant;

/**
 * purchaseReturnedToStorage: 확정 실패 복구 때 구매분이 보관함으로 돌아갔는지(결정 98).
 * licenseGrantDelayedSince: 지급 확인 대기가 경계를 넘은 시각(결정 125). 그 밖에는 null.
 */
public record RefundView(String refundId, String orderNo, long amount, String currency,
        RefundStatus status, Instant receivedAt, Instant completedAt, String rejectionReason, String retryableAttemptId,
        RefundReviewRoundView currentReview, Instant reconsiderationDeadlineAt, boolean reconsiderationAvailable, Instant confirmationDelayedSince,
        RefundAmountView amountDetails, boolean purchaseReturnedToStorage, Instant licenseGrantDelayedSince) {
    public RefundView(String refundId, String orderNo, long amount, String currency,
            RefundStatus status, Instant receivedAt, Instant completedAt, String rejectionReason, String retryableAttemptId,
            RefundReviewRoundView currentReview, Instant reconsiderationDeadlineAt, boolean reconsiderationAvailable, Instant confirmationDelayedSince,
            RefundAmountView amountDetails) {
        this(refundId, orderNo, amount, currency, status, receivedAt, completedAt, rejectionReason, retryableAttemptId,
                currentReview, reconsiderationDeadlineAt, reconsiderationAvailable, confirmationDelayedSince, amountDetails, false, null);
    }

    public RefundView(String refundId, String orderNo, long amount, String currency,
            RefundStatus status, Instant receivedAt, Instant completedAt, String rejectionReason, String retryableAttemptId,
            RefundReviewRoundView currentReview, Instant reconsiderationDeadlineAt, boolean reconsiderationAvailable, Instant confirmationDelayedSince) {
        this(refundId, orderNo, amount, currency, status, receivedAt, completedAt, rejectionReason, retryableAttemptId,
                currentReview, reconsiderationDeadlineAt, reconsiderationAvailable, confirmationDelayedSince, RefundAmountView.unknown(amount), false, null);
    }
}
