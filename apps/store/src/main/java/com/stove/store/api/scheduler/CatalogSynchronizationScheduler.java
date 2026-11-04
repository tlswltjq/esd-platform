package com.stove.store.api.scheduler;

import com.stove.store.core.service.SynchronizeCatalogProducts;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class CatalogSynchronizationScheduler {
    private final SynchronizeCatalogProducts synchronization;
    public void poll() { synchronization.poll(); }
}
