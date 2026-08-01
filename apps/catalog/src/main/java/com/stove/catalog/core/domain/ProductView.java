package com.stove.catalog.core.domain;

/**
 * 상품 읽기 모델.
 *
 * <p>조회 경로가 엔티티를 그대로 흘리지 않는 이유는 두 가지다.
 * <ul>
 *   <li>캐시에 실리는 값이므로 JSON 역직렬화가 가능한 불변 레코드여야 한다
 *       (엔티티는 setter 가 없어 {@code GenericJackson2JsonRedisSerializer} 로 복원되지 않는다).</li>
 *   <li>캐시 페이로드가 API 응답 계약과 분리되어, 응답 필드가 바뀌어도 캐시 스키마는 영향받지 않는다.</li>
 * </ul>
 */
public record ProductView(
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
    public static ProductView from(Product product) {
        return new ProductView(
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
