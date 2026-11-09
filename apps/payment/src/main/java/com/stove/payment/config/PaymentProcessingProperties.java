package com.stove.payment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Payment worker scheduling. {@code retry-delay-ms} is the general retry delay. {@code review-retry-initial-ms} and
 * {@code review-retry-max-ms} are the initial delay and cap of the PG lookup of a held approval (decision 122). The cap
 * may be lowered by configuration but not raised above decision 122's 10 minutes.
 */
@ConfigurationProperties("stove.payment")
public record PaymentProcessingProperties(
        boolean processingEnabled, long pollIntervalMs, int batchSize, long retryDelayMs,
        long reviewRetryInitialMs, long reviewRetryMaxMs) {
    public PaymentProcessingProperties {
        requireRange("poll-interval-ms", pollIntervalMs, 100, 60000);
        requireRange("batch-size", batchSize, 1, 200);
        requireRange("retry-delay-ms", retryDelayMs, 100, 300000);
        requireRange("review-retry-initial-ms", reviewRetryInitialMs, 100, 300000);
        requireRange("review-retry-max-ms", reviewRetryMaxMs, 1000, 600000);
        if (reviewRetryMaxMs < reviewRetryInitialMs)
            throw new IllegalArgumentException("stove.payment.review-retry-max-ms must not be below review-retry-initial-ms");
    }

    private static void requireRange(String name, long value, long min, long max) {
        if (value < min || value > max) {
            throw new IllegalArgumentException("stove.payment." + name + " must be between " + min + " and " + max);
        }
    }
}
