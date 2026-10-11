package com.stove.common.event.payload;

import com.stove.common.event.DomainEvent;
import com.stove.common.event.EventType;
import com.stove.common.event.Topics;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * catalog → store : 상품 마스터 변경. store 는 이 이벤트로 검색 색인을 동기화한다.
 * (조회 트래픽은 store 가 받고, 쓰기 권한은 catalog 만 갖는 CQRS 형태)
 *
 * @param eventId 서비스 간 전달 시 유지하는 이벤트 고유 식별자.
 * @param occurredAt 업무 행동을 기록한 시각.
 * @param productId Catalog 판매 상품 식별자.
 * @param productCode 서비스 간 상품을 연결하는 고유 코드.
 * @param name 조회 화면에 표시할 이름.
 * @param sellerId 판매자를 나타내는 워크스페이스 식별자.
 * @param price 상품 한 개의 가격으로, 최소 화폐 단위의 정수.
 * @param currency 금액의 통화 코드.
 * @param status 변경된 상품의 판매 상태.
 * @param ratingCode 확정된 이용 등급 코드.
 * @param releaseId 대상 릴리스 식별자.
 * @param buildId 배포 파일을 나타내는 빌드 식별자.
 * @param metadataRevision 상점 소개 자료 판본의 식별자.
 * @param productKind 기본 게임·체험판·DLC·에디션·번들 구분.
 * @param parentProductCode 파생 상품이 연결되는 원본 상품 코드.
 * @param editionName 에디션 구분을 위해 표시하는 이름.
 * @param bundleProductCodes 번들에 포함된 상품 코드 목록.
 * @param storefront 공개된 상점 소개 자료의 스냅샷.
 * @param projectionVersion 상품 사본에 전달하는 변경 순번.
 * @param promotions 상품 가격 계산에 사용할 할인 행사 일정 목록.
 */
public record ProductChangedEvent(
        String eventId,
        Instant occurredAt,
        Long productId,
        String productCode,
        String name,
        Long sellerId,
        long price,
        String currency,
        String status,
        String ratingCode,
        Long releaseId,
        Long buildId,
        Long metadataRevision,
        String productKind,
        String parentProductCode,
        String editionName,
        List<String> bundleProductCodes,
        StorefrontSnapshot storefront,
        long projectionVersion,
        List<PromotionWindow> promotions
) implements DomainEvent {

    public static ProductChangedEvent of(Long productId, String productCode, String name, Long sellerId,
                                         long price, String currency, String status, String ratingCode) {
        return new ProductChangedEvent(UUID.randomUUID().toString(), Instant.now(),
                productId, productCode, name, sellerId, price, currency, status, ratingCode,
                null, null, null, "BASIC", null, null, List.of(), null, 0, List.of());
    }

    public static ProductChangedEvent ofRelease(
            Long productId, String productCode, String name, Long sellerId,
            long price, String currency, String status, String ratingCode,
            Long releaseId, Long buildId, Long metadataRevision) {
        return ofRelease(productId, productCode, name, sellerId, price, currency, status,
                ratingCode, releaseId, buildId, metadataRevision, "BASIC", null, null, List.of());
    }

    public static ProductChangedEvent ofRelease(
            Long productId, String productCode, String name, Long sellerId,
            long price, String currency, String status, String ratingCode,
            Long releaseId, Long buildId, Long metadataRevision,
            String productKind, String parentProductCode, String editionName,
            List<String> bundleProductCodes) {
        return ofRelease(productId, productCode, name, sellerId, price, currency, status,
                ratingCode, releaseId, buildId, metadataRevision, productKind,
                parentProductCode, editionName, bundleProductCodes, null);
    }

    public static ProductChangedEvent ofRelease(
            Long productId, String productCode, String name, Long sellerId,
            long price, String currency, String status, String ratingCode,
            Long releaseId, Long buildId, Long metadataRevision,
            String productKind, String parentProductCode, String editionName,
            List<String> bundleProductCodes, StorefrontSnapshot storefront) {
        return ofRelease(productId, productCode, name, sellerId, price, currency, status,
                ratingCode, releaseId, buildId, metadataRevision, productKind,
                parentProductCode, editionName, bundleProductCodes, storefront, 0);
    }

    public static ProductChangedEvent ofRelease(
            Long productId, String productCode, String name, Long sellerId,
            long price, String currency, String status, String ratingCode,
            Long releaseId, Long buildId, Long metadataRevision,
            String productKind, String parentProductCode, String editionName,
            List<String> bundleProductCodes, StorefrontSnapshot storefront, long projectionVersion) {
        return ofRelease(productId, productCode, name, sellerId, price, currency, status,
                ratingCode, releaseId, buildId, metadataRevision, productKind, parentProductCode,
                editionName, bundleProductCodes, storefront, projectionVersion, List.of());
    }

    public static ProductChangedEvent ofRelease(
            Long productId, String productCode, String name, Long sellerId,
            long price, String currency, String status, String ratingCode,
            Long releaseId, Long buildId, Long metadataRevision,
            String productKind, String parentProductCode, String editionName,
            List<String> bundleProductCodes, StorefrontSnapshot storefront, long projectionVersion,
            List<PromotionWindow> promotions) {
        return new ProductChangedEvent(UUID.randomUUID().toString(), Instant.now(),
                productId, productCode, name, sellerId, price, currency, status, ratingCode,
                releaseId, buildId, metadataRevision, productKind, parentProductCode,
                editionName, List.copyOf(bundleProductCodes), storefront, projectionVersion,
                List.copyOf(promotions));
    }

    @Override
    public String eventType() {
        return EventType.PRODUCT_CHANGED;
    }

    @Override
    public String topic() {
        return Topics.CATALOG;
    }

    @Override
    public String partitionKey() {
        return productCode;
    }
}
