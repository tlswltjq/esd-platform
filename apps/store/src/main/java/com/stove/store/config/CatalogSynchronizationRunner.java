package com.stove.store.config;

import com.stove.store.core.service.SynchronizeCatalogProducts;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "stove.store.sync", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CatalogSynchronizationRunner {
    private final SynchronizeCatalogProducts synchronization;
    @EventListener(ApplicationReadyEvent.class)
    public void run() {
        try {
            synchronization.execute();
            log.info("Catalog initial synchronization completed");
        } catch (RuntimeException failure) {
            log.error("Catalog initial synchronization failed; fix the cause and restart Store", failure);
        }
    }
}
