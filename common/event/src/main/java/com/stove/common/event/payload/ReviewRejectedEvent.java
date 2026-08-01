package com.stove.common.event.payload;

import com.stove.common.event.DomainEvent;
import com.stove.common.event.EventType;
import com.stove.common.event.Topics;
import java.time.Instant;
import java.util.UUID;

/** [반려] review → studio : 창작자에게 반려 사유를 돌려준다 */
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
