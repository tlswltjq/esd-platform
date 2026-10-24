package com.stove.order.core.domain;

import java.time.Duration;
import java.time.Instant;

public final class RefundPolicy {
    private static final Duration AUTOMATIC_REFUND_WINDOW = Duration.ofHours(4);

    public boolean isWithinAutomaticRefundWindow(Instant paymentConfirmedAt, Instant receivedAt) {
        Instant deadline = paymentConfirmedAt.plus(AUTOMATIC_REFUND_WINDOW);
        return !receivedAt.isAfter(deadline);
    }
}
