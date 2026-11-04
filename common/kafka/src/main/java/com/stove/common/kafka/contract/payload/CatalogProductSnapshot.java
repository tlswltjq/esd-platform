package com.stove.common.kafka.contract.payload;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/** Public product data only; publication and canonical HTTP use the same current contract. */
public record CatalogProductSnapshot(
        Long productId, String productCode, String title, String shortDescription,
        String thumbnailUrl, long price, String currency, Instant publishedAt,
        String content, String videoUrl, long contentVersion, String buildVersion,
        Instant updatedAt, String saleStatus, long catalogVersion, Boolean listed, List<String> genres) {
    public CatalogProductSnapshot {
        // Preserve the existing sale/listing envelope behavior; genres have no legacy default.
        if (listed == null) listed = saleStatus == null || "ON_SALE".equals(saleStatus);
        if (saleStatus == null && catalogVersion == 0) {
            saleStatus = "ON_SALE";
            catalogVersion = contentVersion;
        }
        if (genres != null) genres = genres.stream().sorted(Comparator.nullsFirst(Comparator.naturalOrder())).toList();
    }
}
