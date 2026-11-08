package com.stove.order.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class RefundLicenseGrantMetricsTest {
    @Test void eachScrapeReadsTheCurrentDelayedGrantWaitsAndReturnsToZeroAfterRecovery() {
        AtomicLong delayed = new AtomicLong();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try {
            new RefundLicenseGrantMetrics(delayed::get).bindTo(registry);
            Gauge gauge = registry.get("stove.order.refund.license.grant.delayed").gauge();
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
