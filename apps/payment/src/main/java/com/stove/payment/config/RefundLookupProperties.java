package com.stove.payment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Refund read-only lookup. {@code late-initial-ms} and {@code late-max-ms} are the initial delay and cap of the lookup once the
 * original 3-hour window has passed and after a failure (decisions 122·128). The cap may be lowered by configuration but not
 * raised above 10 minutes. The delays inside the window are fixed.
 */
@ConfigurationProperties("stove.payment.refund-lookup")
public record RefundLookupProperties(boolean enabled, long lateInitialMs, long lateMaxMs) {
    public RefundLookupProperties {
        requireRange("late-initial-ms", lateInitialMs, 1000, 600000);
        requireRange("late-max-ms", lateMaxMs, 1000, 600000);
        if (lateMaxMs < lateInitialMs)
            throw new IllegalArgumentException("stove.payment.refund-lookup.late-max-ms must not be below late-initial-ms");
    }

    private static void requireRange(String name, long value, long min, long max) {
        if (value < min || value > max)
            throw new IllegalArgumentException("stove.payment.refund-lookup." + name + " must be between " + min + " and " + max);
    }
}
