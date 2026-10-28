package com.stove.order.api.controller.dto;

import com.stove.order.core.domain.RefundReconsiderationReceipt;
import java.time.Instant;

public record RefundReconsiderationResponse(String requestId, String refundId, String reviewId, String previousReviewId, long reviewNo, Instant receivedAt) {
    public static RefundReconsiderationResponse from(RefundReconsiderationReceipt receipt) {
        return new RefundReconsiderationResponse(receipt.requestId(), receipt.refundId(), receipt.reviewId(), receipt.previousReviewId(), receipt.reviewNo(), receipt.receivedAt());
    }
}
