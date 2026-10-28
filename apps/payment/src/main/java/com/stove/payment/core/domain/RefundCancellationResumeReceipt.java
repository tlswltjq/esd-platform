package com.stove.payment.core.domain;

import java.time.Instant;

public record RefundCancellationResumeReceipt(String requestId, String attemptId, String holdId, Instant receivedAt) {
    public RefundCancellationResumeReceipt {
        if (!PaymentValues.uuid(requestId) || !PaymentValues.uuid(attemptId) || !PaymentValues.uuid(holdId)
                || !PaymentValues.microsecond(receivedAt)) throw new IllegalArgumentException("A durable resume receipt is required");
    }
}
