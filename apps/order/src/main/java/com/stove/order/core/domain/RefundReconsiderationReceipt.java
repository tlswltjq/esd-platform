package com.stove.order.core.domain;

import java.time.Instant;

public record RefundReconsiderationReceipt(String requestId, String refundId, String reviewId, String previousReviewId,
        long reviewNo, Instant receivedAt) { }
