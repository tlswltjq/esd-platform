package com.stove.common.event.payload;

import com.stove.common.event.DomainEvent;
import com.stove.common.event.EventType;
import com.stove.common.event.Topics;
import java.time.Instant;
import java.util.UUID;

/**
 * [승인] review → catalog(상품 생성·노출 전환) + studio(프로젝트 상태 반영).
 * 상품 마스터를 만들 수 있을 만큼의 메타데이터를 함께 싣는다.
 */
public record ReviewApprovedEvent(
        String eventId,
        Instant occurredAt,
        Long gameId,
        String productCode,
        String title,
        Long sellerId,
        long price,
        String currency,
        String ratingCode,
        boolean selfRated
) implements DomainEvent {

    public static ReviewApprovedEvent of(Long gameId, String productCode, String title, Long sellerId,
                                         long price, String currency, String ratingCode, boolean selfRated) {
        return new ReviewApprovedEvent(UUID.randomUUID().toString(), Instant.now(),
                gameId, productCode, title, sellerId, price, currency, ratingCode, selfRated);
    }

    @Override
    public String eventType() {
        return EventType.REVIEW_APPROVED;
    }

    @Override
    public String topic() {
        return Topics.REVIEW;
    }

    @Override
    public String partitionKey() {
        return productCode;
    }
}
