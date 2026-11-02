package com.stove.catalog.api.scheduler;

import com.stove.catalog.core.service.ReconcileDownloadStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;

@Slf4j
@RequiredArgsConstructor
public class DownloadStorageScheduler {
    private final ReconcileDownloadStorage reconcile;
    @Scheduled(fixedDelayString = "${stove.catalog.download-storage.poll-interval-ms:5000}")
    public void poll() {
        for (String code : reconcile.due()) {
            try { reconcile.execute(code); }
            catch (RuntimeException failure) { log.warn("Download storage reconciliation transaction failed for {}", code); }
        }
    }
}
