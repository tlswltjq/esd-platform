package com.stove.catalog.api.controller.dto;

import com.stove.catalog.core.domain.PublishedProduct;

import java.time.Instant;

public record ProductDetail(
        Long productId,
        String productCode,
        String title,
        String shortDescription,
        String thumbnailUrl,
        long price,
        String currency,
        Instant publishedAt,
        String content,
        String videoUrl,
        long contentVersion,
        String buildVersion,
        Instant updatedAt) {
    public static ProductDetail from(PublishedProduct p) {
        var s = p.snapshot();
        return new ProductDetail(
                p.productId(),
                s.productCode(),
                s.title(),
                s.shortDescription(),
                s.thumbnailUrl(),
                s.price(),
                s.currency(),
                p.publishedAt(),
                s.content(),
                s.videoUrl(),
                s.contentVersion(),
                s.version(),
                p.updatedAt());
    }
}
