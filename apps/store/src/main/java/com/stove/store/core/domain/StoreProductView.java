package com.stove.store.core.domain;

import com.stove.common.event.payload.StorefrontSnapshot;
import com.stove.common.event.payload.PromotionWindow;
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
        Long listPrice,
        String currency,
        String status,
        boolean visible,
        boolean purchasable,
        String ratingCode,
        Long releaseId,
        Long buildId,
        Long metadataRevision,
        String productKind,
        String parentProductCode,
        String editionName,
        java.util.List<String> bundleProductCodes,
        StorefrontSnapshot storefront,
        String promotionScheduleJson
) implements Serializable {

    public static StoreProductView from(ProductDocument document) {
        return new StoreProductView(
                Long.valueOf(document.getId()),
                document.getProductCode(),
                document.getName(),
                document.getSellerId(),
                document.getPrice(),
                document.getCurrency(),
                document.getStatus(),
                Boolean.TRUE.equals(document.getVisible()),
                document.getReleaseId() != null && "ON_SALE".equals(document.getStatus()),
                document.getRatingCode(),
                document.getReleaseId(),
                document.getBuildId(),
                document.getMetadataRevision(),
                document.getProductKind(),
                document.getParentProductCode(),
                document.getEditionName(),
                document.getBundleProductCodes() == null ? java.util.List.of()
                        : java.util.List.copyOf(document.getBundleProductCodes()),
                document.getStorefront(), document.getPromotionScheduleJson());
    }

    private PromotionWindow activePromotion() {
        return ProductDocument.parsePromotions(promotionScheduleJson).stream()
                .filter(p -> p.activeAt(java.time.Instant.now()))
                .findFirst().orElse(null);
    }

    public Long price() {
        PromotionWindow active = activePromotion();
        return listPrice - (active == null ? 0 : active.discountPerUnit());
    }

    public Long discountAmount() {
        PromotionWindow active = activePromotion();
        return active == null ? 0L : active.discountPerUnit();
    }
    public Long promotionId() {
        PromotionWindow active = activePromotion();
        return active == null ? null : active.id();
    }
    public String discountBearer() {
        PromotionWindow active = activePromotion();
        return active == null ? null : active.bearer();
    }
}
