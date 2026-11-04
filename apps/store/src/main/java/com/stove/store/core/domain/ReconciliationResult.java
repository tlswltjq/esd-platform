package com.stove.store.core.domain;

public enum ReconciliationResult {
    INSERTED, UPDATED, REPAIRED, UNCHANGED, STALE;

    public boolean changed() { return this == INSERTED || this == UPDATED || this == REPAIRED; }
}
