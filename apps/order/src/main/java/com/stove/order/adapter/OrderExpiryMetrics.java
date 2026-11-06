package com.stove.order.adapter;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.util.function.LongSupplier;

public final class OrderExpiryMetrics implements MeterBinder {
    private final LongSupplier expirableOrders;

    public OrderExpiryMetrics(LongSupplier expirableOrders) {
        this.expirableOrders = expirableOrders;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("stove.order.expirable", this, metrics -> metrics.expirableOrders.getAsLong())
                .description("Pending orders past the original payment admission deadline")
                .register(registry);
    }
}
