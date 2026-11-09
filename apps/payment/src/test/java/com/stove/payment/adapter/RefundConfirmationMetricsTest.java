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
        RefundConfirmationMetrics metrics = new RefundConfirmationMetrics(delayed::get, () -> 0);
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

    /** Decision 127: the unattributed gauge is separate, so the critical delay alert keeps its own target and expression. */
    @Test
    void unattributedOpenRefundsAreASeparateGaugeFromDelayedConfirmations() {
        AtomicLong delayed = new AtomicLong();
        AtomicLong unattributed = new AtomicLong();
        RefundConfirmationMetrics metrics = new RefundConfirmationMetrics(delayed::get, unattributed::get);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try {
            metrics.bindTo(registry);
            Gauge open = registry.get("stove.payment.refund.unattributed.open").gauge();
            Gauge late = registry.get("stove.payment.refund.confirmation.delayed").gauge();
            unattributed.set(3);
            assertThat(open.value()).isEqualTo(3);
            assertThat(late.value()).isZero();
            unattributed.set(0);
            assertThat(open.value()).isZero();
        } finally {
            registry.close();
        }
    }
}
