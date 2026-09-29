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
        long projectionVersion
) implements DomainEvent {

    public static ProductChangedEvent of(Long productId, String productCode, String name, Long sellerId,
                                         long price, String currency, String status, String ratingCode) {
        return new ProductChangedEvent(UUID.randomUUID().toString(), Instant.now(),
                productId, productCode, name, sellerId, price, currency, status, ratingCode,
                null, null, null, "BASIC", null, null, List.of(), null, 0);
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
        return new ProductChangedEvent(UUID.randomUUID().toString(), Instant.now(),
                productId, productCode, name, sellerId, price, currency, status, ratingCode,
                releaseId, buildId, metadataRevision, productKind, parentProductCode,
                editionName, List.copyOf(bundleProductCodes), storefront, projectionVersion);
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
