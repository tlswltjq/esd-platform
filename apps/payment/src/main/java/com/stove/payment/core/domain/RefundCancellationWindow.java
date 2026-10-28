package com.stove.payment.core.domain;

import java.time.Duration;
import java.time.Instant;

/** PortOne's published idempotency guarantee, measured conservatively before dispatch. */
public final class RefundCancellationWindow {
    private static final Duration GUARANTEE = Duration.ofHours(3);

    private RefundCancellationWindow() { }

    static Instant deadline(Instant firstPreparedAt) {
        return firstPreparedAt.plus(GUARANTEE);
    }

    public static Instant preparedBy(Instant at) {
        if (!PaymentValues.microsecond(at)) throw new IllegalArgumentException("Database-precision time is required");
        return at.minus(GUARANTEE);
    }
}
