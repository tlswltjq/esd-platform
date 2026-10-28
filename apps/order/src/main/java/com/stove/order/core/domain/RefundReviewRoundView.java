package com.stove.order.core.domain;

import java.time.Instant;

public record RefundReviewRoundView(String reviewId, String refundId, long reviewNo, String applicationRequestId,
        String previousReviewId, String customerReason, Instant receivedAt, RefundReviewReceipt review, String origin, String correctionRequestId, RefundApprovalStopView approvalStop) { }
