package com.stove.catalog.api.controller.dto;

import com.stove.catalog.core.domain.PublishedProduct;

import java.time.Instant;

public record ProductSummary(
        Long productId,
        String productCode,
        String title,
        String shortDescription,
        String thumbnailUrl,
        long price,
        String currency,
        Instant publishedAt) {
    public static ProductSummary from(PublishedProduct p) {
        var s = p.snapshot();
        return new ProductSummary(
                p.productId(),
                s.productCode(),
                s.title(),
                s.shortDescription(),
                s.thumbnailUrl(),
                s.price(),
                s.currency(),
                p.publishedAt());
    }
}
