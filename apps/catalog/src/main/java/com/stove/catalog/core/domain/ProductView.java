package com.stove.catalog.core.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.event.payload.StorefrontSnapshot;
import java.util.List;

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
        Long releaseId,
        Long buildId,
        Long metadataRevision,
        String name,
        Long sellerId,
        long price,
        String currency,
        ProductStatus status,
        String ratingCode,
        String productKind,
        String parentProductCode,
        String editionName,
        List<String> bundleProductCodes,
        StorefrontSnapshot storefront
) {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static ProductView from(Product product) {
        return new ProductView(
                product.getId(),
                product.getProductCode(),
                product.getGameId(),
                product.getCurrentReleaseId(),
                product.getCurrentBuildId(),
                product.getMetadataRevision(),
                product.getName(),
                product.getSellerId(),
                product.getPrice(),
                product.getCurrency(),
                product.getStatus(),
                product.getRatingCode(),
                product.getProductKind(),
                product.getParentProductCode(),
                product.getEditionName(),
                List.copyOf(product.getBundleProductCodes()), readStorefront(product.getStorefrontSnapshotJson()));
    }

    public boolean purchasable() {
        return releaseId != null && status.purchasable();
    }

    public boolean visible() {
        return releaseId != null && (status == ProductStatus.ON_SALE
                || status == ProductStatus.APPROVED || status == ProductStatus.SUSPENDED);
    }

    private static StorefrontSnapshot readStorefront(String json) {
        if (json == null) return null;
        try {
            return MAPPER.readValue(json, StorefrontSnapshot.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("저장된 상점 스냅샷이 올바르지 않습니다.", exception);
        }
    }
}
