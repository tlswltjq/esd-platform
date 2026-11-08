package com.stove.order.adapter;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.util.function.LongSupplier;

public final class RefundLicenseGrantMetrics implements MeterBinder {
    private final LongSupplier delayedWaits;

    public RefundLicenseGrantMetrics(LongSupplier delayedWaits) {
        this.delayedWaits = delayedWaits;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("stove.order.refund.license.grant.delayed", this, metrics -> metrics.delayedWaits.getAsLong())
                .description("Refunds waiting for LicenseGranted past the decision 125 boundary")
                .register(registry);
    }
}
