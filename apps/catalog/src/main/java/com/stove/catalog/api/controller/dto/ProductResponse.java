package com.stove.catalog.api.controller.dto;

import com.stove.catalog.core.domain.ProductStatus;
import com.stove.catalog.core.domain.ProductView;

public record ProductResponse(
        Long productId,
        String productCode,
        Long gameId,
        Long releaseId,
        Long buildId,
        Long metadataRevision,
        String name,
        Long sellerId,
        long price,
        String currency,
        ProductStatus status,
        String ratingCode
) {
    public static ProductResponse from(ProductView product) {
        return new ProductResponse(
                product.productId(),
                product.productCode(),
                product.gameId(),
                product.releaseId(),
                product.buildId(),
                product.metadataRevision(),
                product.name(),
                product.sellerId(),
                product.price(),
                product.currency(),
                product.status(),
                product.ratingCode());
    }
}
