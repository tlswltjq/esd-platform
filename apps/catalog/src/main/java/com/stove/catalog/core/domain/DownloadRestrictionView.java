package com.stove.catalog.core.domain;

import java.time.Instant;

public record DownloadRestrictionView(String productCode, boolean restricted, boolean downloadUnavailable,
        long restrictionVersion, String storageStatus, Instant storageCheckedAt, Instant nextAttemptAt) {
    public static DownloadRestrictionView from(CatalogProduct product) {
        return new DownloadRestrictionView(product.getProductCode(), product.isDownloadRestricted(),
                product.isDownloadUnavailable(), product.getDownloadRestrictionVersion(), product.getDownloadStorageStatus(),
                product.getDownloadStorageCheckedAt(), product.getDownloadStorageNextAttemptAt());
    }
}
