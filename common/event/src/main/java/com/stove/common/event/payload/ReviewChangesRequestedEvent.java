package com.stove.common.event.payload;

import com.stove.common.event.DomainEvent;
import com.stove.common.event.EventType;
import com.stove.common.event.Topics;
import java.time.Instant;
import java.util.UUID;

public record ReviewChangesRequestedEvent(
        String eventId, Instant occurredAt,
        Long reviewId, Long submissionId, String reviewType,
        String productCode, String reasonCode, String feedback
) implements DomainEvent {
    public static ReviewChangesRequestedEvent of(Long reviewId, Long submissionId, String reviewType,
                                                 String productCode, String reasonCode, String feedback) {
        return new ReviewChangesRequestedEvent(UUID.randomUUID().toString(), Instant.now(), reviewId,
                submissionId, reviewType, productCode, reasonCode, feedback);
    }
    @Override public String eventType() { return EventType.REVIEW_CHANGES_REQUESTED; }
    @Override public String topic() { return Topics.REVIEW; }
    @Override public String partitionKey() { return productCode; }
}
