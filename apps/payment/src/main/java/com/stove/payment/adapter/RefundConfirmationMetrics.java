package com.stove.payment.adapter;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.util.function.LongSupplier;

public final class RefundConfirmationMetrics implements MeterBinder {
    private final LongSupplier delayedConfirmations;

    public RefundConfirmationMetrics(LongSupplier delayedConfirmations) {
        this.delayedConfirmations = delayedConfirmations;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("stove.payment.refund.confirmation.delayed", this,
                metrics -> metrics.delayedConfirmations.getAsLong())
                .description("Unconfirmed PortOne TEST refunds past the original cancellation window")
                .register(registry);
    }
}
