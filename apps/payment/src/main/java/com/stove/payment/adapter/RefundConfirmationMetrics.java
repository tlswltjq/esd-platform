package com.stove.payment.adapter;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.util.function.LongSupplier;

public final class RefundConfirmationMetrics implements MeterBinder {
    private final LongSupplier delayedConfirmations;
    private final LongSupplier unattributedOpen;
    private final LongSupplier lateCancellations;

    public RefundConfirmationMetrics(LongSupplier delayedConfirmations, LongSupplier unattributedOpen) {
        this(delayedConfirmations, unattributedOpen, () -> 0);
    }

    public RefundConfirmationMetrics(LongSupplier delayedConfirmations, LongSupplier unattributedOpen, LongSupplier lateCancellations) {
        this.delayedConfirmations = delayedConfirmations; this.unattributedOpen = unattributedOpen; this.lateCancellations = lateCancellations;
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
        // Decision 128: a refund already failed (license restored), yet a later lookup found the payment fully cancelled.
        Gauge.builder("stove.payment.refund.failed.late_cancellation", this, metrics -> metrics.lateCancellations.getAsLong())
                .description("Failed refunds whose original payment a later PG lookup found fully cancelled; status and license were not changed")
                .register(registry);
    }
}
