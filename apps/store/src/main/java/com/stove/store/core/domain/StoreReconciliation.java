package com.stove.store.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "store_reconciliation")
public class StoreReconciliation {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false) private Long productId;
    @Column(nullable = false, length = 50) private String productCode;
    @Column(nullable = false) private long catalogVersion;
    @Column(nullable = false, length = 20) private String action;
    @Column(columnDefinition = "longtext") private String beforeSnapshot;
    @Column(nullable = false, columnDefinition = "longtext") private String afterSnapshot;
    @Column(nullable = false) private Instant sourceReadAt;
    @Column(nullable = false) private Instant recordedAt;

    protected StoreReconciliation() {}

    public static StoreReconciliation record(CanonicalProduct source, ReconciliationResult result, String before,
            String after, Instant sourceReadAt, Instant recordedAt) {
        StoreReconciliation entry = new StoreReconciliation();
        entry.productId = source.snapshot().productId();
        entry.productCode = source.requestedCode();
        entry.catalogVersion = source.snapshot().catalogVersion();
        entry.action = result.name();
        entry.beforeSnapshot = before;
        entry.afterSnapshot = after;
        entry.sourceReadAt = sourceReadAt;
        entry.recordedAt = recordedAt;
        return entry;
    }
}
