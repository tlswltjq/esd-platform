package com.stove.common.event.payload;

import com.stove.common.event.DomainEvent;
import com.stove.common.event.EventType;
import com.stove.common.event.Topics;
import java.time.Instant;
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
        String ratingCode
) implements DomainEvent {

    public static ProductChangedEvent of(Long productId, String productCode, String name, Long sellerId,
                                         long price, String currency, String status, String ratingCode) {
        return new ProductChangedEvent(UUID.randomUUID().toString(), Instant.now(),
                productId, productCode, name, sellerId, price, currency, status, ratingCode);
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
