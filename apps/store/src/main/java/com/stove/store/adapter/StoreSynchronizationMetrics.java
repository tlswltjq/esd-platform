package com.stove.store.adapter;

import com.stove.store.core.domain.CatalogSyncStatus;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class StoreSynchronizationMetrics implements MeterBinder {
    private final Supplier<CatalogSyncStatus> status;
    private final LongSupplier successfulRuns;
    private final LongSupplier failedRuns;

    public void bindTo(MeterRegistry registry) {
        Gauge.builder("stove.store.sync.ready", this, value -> value.status.get().ready() ? 1 : 0).register(registry);
        Gauge.builder("stove.store.sync.running", this, value -> value.status.get().running() ? 1 : 0).register(registry);
        Gauge.builder("stove.store.sync.runs", this, value -> value.successfulRuns.getAsLong()).tag("result", "success").register(registry);
        Gauge.builder("stove.store.sync.runs", this, value -> value.failedRuns.getAsLong()).tag("result", "failure").register(registry);
        Gauge.builder("stove.store.sync.last.success.timestamp", this, value -> {
            CatalogSyncStatus current = value.status.get();
            return current.lastSuccessAt() == null ? 0 : current.lastSuccessAt().getEpochSecond();
        }).register(registry);
        Gauge.builder("stove.store.sync.last.scanned", this, value -> value.status.get().scanned()).register(registry);
        Gauge.builder("stove.store.sync.last.changed", this, value -> value.status.get().changed()).register(registry);
        Gauge.builder("stove.store.sync.last.failed", this, value -> value.status.get().failed()).register(registry);
        Gauge.builder("stove.store.sync.last.skipped.newer", this, value -> value.status.get().skippedNewer()).register(registry);
    }
}
