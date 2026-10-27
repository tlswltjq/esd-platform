package com.stove.payment.core.domain;

import java.time.Duration;
import java.time.Instant;

/** PortOne's published idempotency guarantee, measured conservatively before dispatch. */
final class RefundCancellationWindow {
    private static final Duration GUARANTEE = Duration.ofHours(3);

    private RefundCancellationWindow() { }

    static Instant deadline(Instant firstPreparedAt) {
        return firstPreparedAt.plus(GUARANTEE);
    }
}
