package com.stove.payment.adapter;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.util.function.LongSupplier;

public final class RefundConfirmationMetrics implements MeterBinder {
    private final LongSupplier delayedConfirmations;
    private final LongSupplier unattributedOpen;

    public RefundConfirmationMetrics(LongSupplier delayedConfirmations, LongSupplier unattributedOpen) {
        this.delayedConfirmations = delayedConfirmations; this.unattributedOpen = unattributedOpen;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("stove.payment.refund.confirmation.delayed", this,
                metrics -> metrics.delayedConfirmations.getAsLong())
                .description("Unconfirmed PortOne TEST refunds past the original cancellation window")
                .register(registry);
        // Decision 127: money already returned by a full PG cancellation, but nobody has closed the attribution yet.
        Gauge.builder("stove.payment.refund.unattributed.open", this, metrics -> metrics.unattributedOpen.getAsLong())
                .description("PortOne TEST refunds fully cancelled at the PG, not attributed to our attempt and not closed by an operator")
                .register(registry);
    }
}
