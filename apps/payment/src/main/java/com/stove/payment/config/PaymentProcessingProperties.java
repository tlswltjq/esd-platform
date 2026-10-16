package com.stove.payment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("stove.payment")
public record PaymentProcessingProperties(
        boolean processingEnabled, long pollIntervalMs, int batchSize, long retryDelayMs) {
    public PaymentProcessingProperties {
        requireRange("poll-interval-ms", pollIntervalMs, 100, 60000);
        requireRange("batch-size", batchSize, 1, 200);
        requireRange("retry-delay-ms", retryDelayMs, 100, 300000);
    }

    private static void requireRange(String name, long value, long min, long max) {
        if (value < min || value > max) {
            throw new IllegalArgumentException("stove.payment." + name + " must be between " + min + " and " + max);
        }
    }
}
