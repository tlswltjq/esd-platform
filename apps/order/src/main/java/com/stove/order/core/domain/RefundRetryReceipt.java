package com.stove.order.core.domain;

import java.time.Instant;

public record RefundRetryReceipt(String refundId, String previousAttemptId, String suspensionRequestEventId, Instant receivedAt) {
    public RefundRetryReceipt {
        if (!RefundRestorationValues.uuid(refundId) || !RefundRestorationValues.uuid(previousAttemptId)
                || !RefundRestorationValues.text(suspensionRequestEventId, 64) || !RefundRestorationValues.time(receivedAt))
            throw new IllegalArgumentException("A durable retry receipt is required");
    }
}
