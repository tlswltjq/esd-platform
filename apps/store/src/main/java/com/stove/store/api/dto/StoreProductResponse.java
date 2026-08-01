package com.stove.store.api.dto;

import com.stove.store.domain.ProductDocument;
import java.io.Serializable;

/** Redis 캐시에 담기므로 직렬화 가능한 단순 레코드로 유지한다. */
public record StoreProductResponse(
        Long productId,
        String productCode,
        String name,
        Long sellerId,
        Long price,
        String currency,
        String ratingCode
) implements Serializable {

    public static StoreProductResponse from(ProductDocument document) {
        return new StoreProductResponse(
                Long.valueOf(document.getId()),
                document.getProductCode(),
                document.getName(),
                document.getSellerId(),
                document.getPrice(),
                document.getCurrency(),
                document.getRatingCode());
    }
}
