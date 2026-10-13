package com.stove.store.core.domain;

import com.stove.common.kafka.contract.payload.StorefrontSnapshot;
import com.stove.common.kafka.contract.payload.PromotionWindow;
import java.io.Serializable;

/**
 * 진열 읽기 모델. Redis 캐시에 담기므로 직렬화 가능한 단순 레코드로 유지한다.
 *
 * <p>캐시에 실리는 값을 API 응답 계약과 분리해 두면, 응답 필드를 바꿔도
 * 캐시 스키마는 그대로 유지된다.
 *
 * @param productId Catalog 판매 상품 식별자.
 * @param productCode 서비스 간 상품을 연결하는 고유 코드.
 * @param name 조회 화면에 표시할 이름.
 * @param sellerId 판매자를 나타내는 워크스페이스 식별자.
 * @param listPrice 할인을 적용하기 전 상품 정가.
 * @param currency 금액의 통화 코드.
 * @param status Catalog에서 전달받은 상품 판매 상태.
 * @param visible 상점 조회 결과에 노출할 수 있는지 여부.
 * @param purchasable 현재 판매 상태와 공개 여부를 기준으로 구매 가능한지 여부.
 * @param ratingCode 확정된 이용 등급 코드.
 * @param releaseId 대상 릴리스 식별자.
 * @param buildId 배포 파일을 나타내는 빌드 식별자.
 * @param metadataRevision 상점 소개 자료 판본의 식별자.
 * @param productKind 기본 게임·체험판·DLC·에디션·번들 구분.
 * @param parentProductCode 파생 상품이 연결되는 원본 상품 코드.
 * @param editionName 에디션 구분을 위해 표시하는 이름.
 * @param bundleProductCodes 번들에 포함된 상품 코드 목록.
 * @param storefront 공개된 상점 소개 자료의 스냅샷.
 * @param promotionScheduleJson 가격 계산에 사용할 할인 행사 일정 JSON.
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
