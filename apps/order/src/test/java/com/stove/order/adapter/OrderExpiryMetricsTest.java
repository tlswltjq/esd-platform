package com.stove.order.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class OrderExpiryMetricsTest {
    @Test
    void eachScrapeReadsTheCurrentExpiryBacklogAndReturnsToZeroAfterRecovery() {
        AtomicLong pending = new AtomicLong();
        OrderExpiryMetrics metrics = new OrderExpiryMetrics(pending::get);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try {
            metrics.bindTo(registry);
            Gauge gauge = registry.get("stove.order.expirable").gauge();

            assertThat(gauge.value()).isZero();
            pending.set(2001);
            assertThat(gauge.value()).isEqualTo(2001);
            pending.set(0);
            assertThat(gauge.value()).isZero();
        } finally {
            registry.close();
        }
    }
}
