package com.stove.payment.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class RefundConfirmationMetricsTest {
    @Test
    void eachScrapeReadsTheCurrentDelayedCountAndReturnsToZeroAfterRecovery() {
        AtomicLong delayed = new AtomicLong();
        RefundConfirmationMetrics metrics = new RefundConfirmationMetrics(delayed::get);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try {
            metrics.bindTo(registry);
            Gauge gauge = registry.get("stove.payment.refund.confirmation.delayed").gauge();

            assertThat(gauge.value()).isZero();
            delayed.set(2);
            assertThat(gauge.value()).isEqualTo(2);
            delayed.set(0);
            assertThat(gauge.value()).isZero();
        } finally {
            registry.close();
        }
    }
}
