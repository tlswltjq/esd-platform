package com.stove.store.api.controller.dto;

import com.stove.store.core.domain.StoreProductView;

public record StoreProductResponse(
        Long productId,
        String productCode,
        String name,
        Long sellerId,
        Long price,
        String currency,
        String ratingCode
) {
    public static StoreProductResponse from(StoreProductView product) {
        return new StoreProductResponse(
                product.productId(),
                product.productCode(),
                product.name(),
                product.sellerId(),
                product.price(),
                product.currency(),
                product.ratingCode());
    }
}
