package com.stove.catalog.core.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.kafka.contract.payload.StorefrontSnapshot;
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
 *
 * @param productId Catalog 판매 상품 식별자.
 * @param productCode 서비스 간 상품을 연결하는 고유 코드.
 * @param gameId Studio 게임 프로젝트 식별자.
 * @param releaseId 대상 릴리스 식별자.
 * @param buildId 배포 파일을 나타내는 빌드 식별자.
 * @param metadataRevision 상점 소개 자료 판본의 식별자.
 * @param name 조회 화면에 표시할 이름.
 * @param sellerId 판매자를 나타내는 워크스페이스 식별자.
 * @param price 상품 한 개의 가격으로, 최소 화폐 단위의 정수.
 * @param currency 금액의 통화 코드.
 * @param status 상품의 심사 및 판매 진행 상태.
 * @param ratingCode 확정된 이용 등급 코드.
 * @param productKind 기본 게임·체험판·DLC·에디션·번들 구분.
 * @param parentProductCode 파생 상품이 연결되는 원본 상품 코드.
 * @param editionName 에디션 구분을 위해 표시하는 이름.
 * @param bundleProductCodes 번들에 포함된 상품 코드 목록.
 * @param storefront 공개된 상점 소개 자료의 스냅샷.
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
    /** 도메인 자료를 JSON으로 변환하거나 복원하는 매퍼. */
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
