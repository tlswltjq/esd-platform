package com.stove.order.core.domain;

import java.time.Instant;

public record RefundView(String refundId, String orderNo, long amount, String currency,
        RefundStatus status, Instant receivedAt, Instant completedAt, String rejectionReason, String retryableAttemptId,
        RefundReviewRoundView currentReview, Instant reconsiderationDeadlineAt, boolean reconsiderationAvailable, Instant confirmationDelayedSince) { }
