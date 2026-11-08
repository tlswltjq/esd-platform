package com.stove.order.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;

class RefundLicenseGrantMetricsTest {
    @Test void eachScrapeReadsTheCurrentDelayedGrantWaitsAndReturnsToZeroAfterRecovery() {
        AtomicLong delayed = new AtomicLong();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try {
            new RefundLicenseGrantMetrics(Map.of("PENDING_LICENSE_GRANT", delayed::get)).bindTo(registry);
            Gauge gauge = registry.get("stove.order.refund.license.grant.delayed").tag("status", "PENDING_LICENSE_GRANT").gauge();
            assertThat(gauge.value()).isZero();
            delayed.set(2);
            assertThat(gauge.value()).isEqualTo(2);
            delayed.set(0);
            assertThat(gauge.value()).isZero();
        } finally {
            registry.close();
        }
    }

    /** 결정 125 게이지 이름을 그대로 두고 status 라벨로 상태를 나눈다. 라벨 없이 합하면 전체 지연 건수다. */
    @Test void eachWaitStatusIsOneSeriesOfTheSameGauge() {
        Map<String, LongSupplier> counts = new LinkedHashMap<>();
        counts.put("PENDING_LICENSE_GRANT", () -> 1);
        counts.put("PENDING_LICENSE_SUSPENSION", () -> 2);
        counts.put("PENDING_LICENSE_RESTORATION", () -> 3);
        counts.put("PENDING_APPROVAL_STOP", () -> 4);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try {
            new RefundLicenseGrantMetrics(counts).bindTo(registry);
            assertThat(registry.get("stove.order.refund.license.grant.delayed").gauges()).hasSize(4);
            counts.forEach((status, count) -> assertThat(registry.get("stove.order.refund.license.grant.delayed")
                    .tag("status", status).gauge().value()).as(status).isEqualTo(count.getAsLong()));
        } finally {
            registry.close();
        }
    }
}
