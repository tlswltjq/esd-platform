package com.stove.catalog.api.dto;

import com.stove.catalog.domain.Product;
import com.stove.catalog.domain.ProductStatus;

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
    public static ProductResponse from(Product product) {
        return new ProductResponse(
                product.getId(),
                product.getProductCode(),
                product.getGameId(),
                product.getName(),
                product.getSellerId(),
                product.getPrice(),
                product.getCurrency(),
                product.getStatus(),
                product.getRatingCode());
    }
}
