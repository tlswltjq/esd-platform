package com.stove.catalog.api.controller.dto;

import com.stove.catalog.core.domain.ProductStatus;
import com.stove.catalog.core.domain.ProductView;

public record ProductResponse(
        Long productId,
        String productCode,
        Long gameId,
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
                product.name(),
                product.sellerId(),
                product.price(),
                product.currency(),
                product.status(),
                product.ratingCode());
    }
}
