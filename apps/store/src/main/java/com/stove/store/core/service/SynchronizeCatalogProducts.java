package com.stove.store.core.service;

import com.stove.common.web.error.BusinessException;
import com.stove.store.core.domain.CatalogSyncStatus;
import com.stove.store.core.domain.CatalogUnavailableException;
import com.stove.store.core.domain.ReconciliationResult;
import com.stove.store.core.domain.StoreErrorCode;
import com.stove.store.core.port.CatalogProducts;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class SynchronizeCatalogProducts {
    private final CatalogProducts catalog;
    private final ReconcileCatalogProduct reconciliation;
    private final Clock clock;
    private final Duration retryDelay;
    private final Duration reconcileInterval;
    private final AtomicBoolean active = new AtomicBoolean();
    private final AtomicLong successfulRuns = new AtomicLong();
    private final AtomicLong failedRuns = new AtomicLong();
    private final AtomicReference<CatalogSyncStatus> status = new AtomicReference<>(
            new CatalogSyncStatus(false, false, null, null, Instant.EPOCH, 0, 0, 0, 0, null));

    public SynchronizeCatalogProducts(CatalogProducts catalog, ReconcileCatalogProduct reconciliation, Clock clock,
            Duration retryDelay, Duration reconcileInterval) {
        if (retryDelay == null || retryDelay.isNegative() || retryDelay.isZero()
                || reconcileInterval == null || reconcileInterval.isNegative() || reconcileInterval.isZero())
            throw new IllegalArgumentException("Store synchronization intervals must be positive");
        this.catalog = catalog;
        this.reconciliation = reconciliation;
        this.clock = clock;
        this.retryDelay = retryDelay;
        this.reconcileInterval = reconcileInterval;
    }

    public void poll() {
        if (clock.instant().isBefore(status.get().nextAttemptAt())) return;
        try { execute(); }
        catch (RuntimeException failure) {
            // Provider messages may include URLs or credentials; publish only the failure category.
            log.warn("Catalog automatic synchronization failed: type={}", failure.getClass().getSimpleName());
        }
    }

    /** Also used for explicit full-pass contract checks; periodic production calls use poll(). */
    public void execute() {
        if (!active.compareAndSet(false, true)) return;
        CatalogSyncStatus previous = status.get();
        Instant started = clock.instant();
        long scanned = 0;
        long changed = 0;
        long failed = 0;
        long skipped = 0;
        status.set(new CatalogSyncStatus(previous.ready(), true, started, previous.lastSuccessAt(),
                previous.nextAttemptAt(), 0, 0, 0, 0, null));
        try {
            RuntimeException firstFailure = null;
            for (int page = 0; ; page++) {
                List<String> codes = catalog.codes(page);
                if (codes == null) throw new IllegalStateException("Invalid Catalog product list");
                if (codes.isEmpty()) break;
                for (String code : codes) {
                    scanned++;
                    try {
                        ReconciliationResult result = reconciliation.execute(code);
                        if (result.changed()) changed++;
                        if (result == ReconciliationResult.STALE) skipped++;
                    } catch (RuntimeException failure) {
                        failed++;
                        if (firstFailure == null) firstFailure = failure;
                        String identifier = code != null && code.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,49}") ? code : "invalid-code";
                        log.warn("Catalog product reconciliation failed: product={} type={}", identifier, failure.getClass().getSimpleName());
                        if (failure instanceof CatalogUnavailableException) throw failure;
                    }
                    status.set(new CatalogSyncStatus(previous.ready(), true, started, previous.lastSuccessAt(),
                            previous.nextAttemptAt(), scanned, changed, failed, skipped,
                            firstFailure == null ? null : firstFailure.getClass().getSimpleName()));
                }
            }
            if (firstFailure != null) throw firstFailure;
            Instant completed = clock.instant();
            status.set(new CatalogSyncStatus(true, false, started, completed, completed.plus(reconcileInterval),
                    scanned, changed, 0, skipped, null));
            successfulRuns.incrementAndGet();
        } catch (RuntimeException failure) {
            status.set(new CatalogSyncStatus(previous.ready(), false, started, previous.lastSuccessAt(),
                    clock.instant().plus(retryDelay), scanned, changed, failed, skipped, failure.getClass().getSimpleName()));
            failedRuns.incrementAndGet();
            throw failure;
        } finally { active.set(false); }
    }

    public CatalogSyncStatus status() { return status.get(); }
    public long successfulRuns() { return successfulRuns.get(); }
    public long failedRuns() { return failedRuns.get(); }
    public void requireReady() {
        if (!status.get().ready()) throw new BusinessException(StoreErrorCode.NOT_READY);
    }
}
