package com.stove.store.core.domain;

import java.time.Instant;

public record CatalogSyncStatus(boolean ready, boolean running, Instant startedAt, Instant lastSuccessAt,
        Instant nextAttemptAt, long scanned, long changed, long failed, long skippedNewer, String failureType) {}
