package com.stove.store.core.domain;

import java.io.Serializable;

/**
 * 진열 읽기 모델. Redis 캐시에 담기므로 직렬화 가능한 단순 레코드로 유지한다.
 *
 * <p>캐시에 실리는 값을 API 응답 계약과 분리해 두면, 응답 필드를 바꿔도
 * 캐시 스키마는 그대로 유지된다.
 */
public record StoreProductView(
        Long productId,
        String productCode,
        String name,
        Long sellerId,
        Long price,
        String currency,
        String ratingCode
) implements Serializable {

    public static StoreProductView from(ProductDocument document) {
        return new StoreProductView(
                Long.valueOf(document.getId()),
                document.getProductCode(),
                document.getName(),
                document.getSellerId(),
                document.getPrice(),
                document.getCurrency(),
                document.getRatingCode());
    }
}
