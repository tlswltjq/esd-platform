package com.stove.order.api.controller.dto;

import com.stove.order.core.domain.RefundRetryReceipt;
import java.time.Instant;

public record RefundRetryResponse(String refundId, String previousAttemptId, Instant receivedAt) {
    public static RefundRetryResponse from(RefundRetryReceipt receipt) {
        return new RefundRetryResponse(receipt.refundId(), receipt.previousAttemptId(), receipt.receivedAt());
    }
}
