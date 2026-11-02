package com.stove.common.kafka.contract.payload;

import java.time.Instant;

/** Public product data only; shared by publication events and the Store projection. */
public record CatalogProductSnapshot(
        Long productId, String productCode, String title, String shortDescription,
        String thumbnailUrl, long price, String currency, Instant publishedAt,
        String content, String videoUrl, long contentVersion, String buildVersion,
        Instant updatedAt, String saleStatus, long catalogVersion, Boolean listed) {
    public CatalogProductSnapshot {
        // Older publication payloads use the automatic sale-based listing rule.
        if (listed == null) listed = saleStatus == null || "ON_SALE".equals(saleStatus);
        // Published events written before sale-state support have neither field.
        if (saleStatus == null && catalogVersion == 0) {
            saleStatus = "ON_SALE";
            catalogVersion = contentVersion;
        }
    }

    public CatalogProductSnapshot(Long productId, String productCode, String title, String shortDescription,
            String thumbnailUrl, long price, String currency, Instant publishedAt, String content,
            String videoUrl, long contentVersion, String buildVersion, Instant updatedAt, String saleStatus, long catalogVersion) {
        this(productId, productCode, title, shortDescription, thumbnailUrl, price, currency, publishedAt,
                content, videoUrl, contentVersion, buildVersion, updatedAt, saleStatus, catalogVersion, null);
    }

    public CatalogProductSnapshot(Long productId, String productCode, String title, String shortDescription,
            String thumbnailUrl, long price, String currency, Instant publishedAt, String content,
            String videoUrl, long contentVersion, String buildVersion, Instant updatedAt) {
        this(productId, productCode, title, shortDescription, thumbnailUrl, price, currency, publishedAt,
                content, videoUrl, contentVersion, buildVersion, updatedAt, "ON_SALE", contentVersion);
    }
}
