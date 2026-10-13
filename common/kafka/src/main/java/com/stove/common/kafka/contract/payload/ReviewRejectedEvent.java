package com.stove.common.kafka.contract.payload;

import com.stove.common.kafka.contract.DomainEvent;
import com.stove.common.kafka.contract.EventType;
import com.stove.common.kafka.contract.Topics;
import java.time.Instant;
import java.util.UUID;

/**
 *  [반려] review → studio : 창작자에게 반려 사유를 돌려준다
 *
 * @param eventId 서비스 간 전달 시 유지하는 이벤트 고유 식별자.
 * @param occurredAt 업무 행동을 기록한 시각.
 * @param gameId Studio 게임 프로젝트 식별자.
 * @param productCode 서비스 간 상품을 연결하는 고유 코드.
 * @param reasonCode 처리 결과 또는 요청 사유를 구분하는 코드.
 * @param reason 처리 결과 또는 요청 사유에 대한 설명.
 */
public record ReviewRejectedEvent(
        String eventId,
        Instant occurredAt,
        Long gameId,
        String productCode,
        String reasonCode,
        String reason
) implements DomainEvent {

    public static ReviewRejectedEvent of(Long gameId, String productCode, String reasonCode, String reason) {
        return new ReviewRejectedEvent(UUID.randomUUID().toString(), Instant.now(),
                gameId, productCode, reasonCode, reason);
    }

    @Override
    public String eventType() {
        return EventType.REVIEW_REJECTED;
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
