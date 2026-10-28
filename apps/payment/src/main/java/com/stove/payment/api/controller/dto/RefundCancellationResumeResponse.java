package com.stove.payment.api.controller.dto;

import com.stove.payment.core.domain.RefundCancellationResumeReceipt;
import java.time.Instant;

public record RefundCancellationResumeResponse(String requestId, String attemptId, String holdId, Instant receivedAt) {
    public static RefundCancellationResumeResponse from(RefundCancellationResumeReceipt receipt) {
        return new RefundCancellationResumeResponse(receipt.requestId(), receipt.attemptId(), receipt.holdId(), receipt.receivedAt());
    }
}
