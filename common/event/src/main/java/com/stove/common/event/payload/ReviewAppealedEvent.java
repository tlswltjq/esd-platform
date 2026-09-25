package com.stove.common.event.payload;

import com.stove.common.event.DomainEvent;
import com.stove.common.event.EventType;
import com.stove.common.event.Topics;
import java.time.Instant;
import java.util.UUID;

public record ReviewAppealedEvent(
        String eventId, Instant occurredAt, Long reviewId, Long submissionId,
        String reviewType, String productCode, String reason
) implements DomainEvent {
    public static ReviewAppealedEvent of(Long reviewId, Long submissionId, String reviewType,
                                         String productCode, String reason) {
        return new ReviewAppealedEvent(UUID.randomUUID().toString(), Instant.now(), reviewId,
                submissionId, reviewType, productCode, reason);
    }
    @Override public String eventType() { return EventType.REVIEW_APPEALED; }
    @Override public String topic() { return Topics.REVIEW; }
    @Override public String partitionKey() { return productCode; }
}
