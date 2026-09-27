package com.stove.store.api.controller.dto;

import com.stove.store.core.domain.StoreProductView;
import java.util.List;

public record StoreProductResponse(
        Long productId,
        String productCode,
        String name,
        Long sellerId,
        Long price,
        String currency,
        String ratingCode,
        Long releaseId,
        Long buildId,
        Long metadataRevision,
        String productKind,
        String parentProductCode,
        String editionName,
        List<String> bundleProductCodes
) {
    public static StoreProductResponse from(StoreProductView product) {
        return new StoreProductResponse(
                product.productId(),
                product.productCode(),
                product.name(),
                product.sellerId(),
                product.price(),
                product.currency(),
                product.ratingCode(),
                product.releaseId(),
                product.buildId(),
                product.metadataRevision(),
                product.productKind(),
                product.parentProductCode(),
                product.editionName(),
                product.bundleProductCodes());
    }
}
