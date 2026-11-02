package com.stove.catalog.core.domain;

import com.stove.common.kafka.contract.payload.ProjectSnapshot;
import com.stove.common.kafka.contract.payload.CatalogProductSnapshot;

import java.time.Instant;

public record PublishedProduct(
        Long productId, ProjectSnapshot snapshot, Instant publishedAt, Instant updatedAt,
        String saleStatus, long catalogVersion, boolean listed) {
    public CatalogProductSnapshot publicSnapshot() {
        return new CatalogProductSnapshot(productId, snapshot.productCode(), snapshot.title(), snapshot.shortDescription(),
                snapshot.thumbnailUrl(), snapshot.price(), snapshot.currency(), publishedAt, snapshot.content(),
                snapshot.videoUrl(), snapshot.contentVersion(), snapshot.version(), updatedAt, saleStatus, catalogVersion, listed);
    }
}
