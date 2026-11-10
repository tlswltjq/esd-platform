package com.stove.payment.core.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * The PG lookup delay of a refund execution once its original 3-hour window has passed, and after its failure is confirmed
 * (decision 122's formula applied to refunds, decision 128's "low frequency"): {@code min(max, max(initial, at - firstPreparedAt))}.
 * The delays inside the window (5, 10, 20, then 60 seconds) are separate and unchanged. The cap may be lowered by
 * configuration but never raised above 10 minutes.
 */
public record RefundLookupInterval(Duration initial, Duration max) {
    public static final Duration CAP = Duration.ofMinutes(10);
    public static final RefundLookupInterval DEFAULT = new RefundLookupInterval(Duration.ofSeconds(60), CAP);

    public RefundLookupInterval {
        if (initial == null || max == null || initial.isNegative() || initial.isZero() || max.compareTo(initial) < 0
                || max.compareTo(CAP) > 0 || initial.getNano() % 1000 != 0 || max.getNano() % 1000 != 0)
            throw new IllegalArgumentException("A positive database-precision refund lookup delay not above its 10-minute cap is required");
    }

    public Duration after(Instant firstPreparedAt, Instant at) {
        if (firstPreparedAt == null || at == null) throw new IllegalArgumentException("The preparation and lookup times are required");
        Duration age = Duration.between(firstPreparedAt, at);
        if (age.compareTo(initial) < 0) return initial;
        return age.compareTo(max) > 0 ? max : age;
    }
}
