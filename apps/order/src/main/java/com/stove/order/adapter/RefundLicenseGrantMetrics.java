package com.stove.order.adapter;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * 결정 125 게이지. 이름은 그대로 두고 대기 상태를 {@code status} 라벨로 나눈다(지급 확인·정지·복구·승인 중단).
 * 라벨 없이 쓰던 식(예: {@code stove_order_refund_license_grant_delayed > 0}, {@code sum(...)})은 그대로 동작한다.
 */
public final class RefundLicenseGrantMetrics implements MeterBinder {
    private final Map<String, LongSupplier> delayedByStatus;

    public RefundLicenseGrantMetrics(Map<String, LongSupplier> delayedByStatus) {
        if (delayedByStatus == null || delayedByStatus.isEmpty()) throw new IllegalArgumentException("Delayed wait counts are required");
        this.delayedByStatus = new LinkedHashMap<>(delayedByStatus);
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        delayedByStatus.forEach((status, delayed) ->
                Gauge.builder("stove.order.refund.license.grant.delayed", delayed, LongSupplier::getAsLong)
                        .description("Refunds waiting for a License step event past the decision 125 boundary")
                        .tag("status", status)
                        .register(registry));
    }
}
