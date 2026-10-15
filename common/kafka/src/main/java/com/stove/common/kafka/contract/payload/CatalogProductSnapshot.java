package com.stove.common.kafka.contract.payload;

import java.time.Instant;

/** Public product data only; shared by publication events and the Store projection. */
public record CatalogProductSnapshot(
        Long productId, String productCode, String title, String shortDescription,
        String thumbnailUrl, long price, String currency, Instant publishedAt,
        String content, String videoUrl, long contentVersion, String buildVersion,
        Instant updatedAt) {}
