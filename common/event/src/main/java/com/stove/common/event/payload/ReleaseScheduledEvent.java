package com.stove.common.event.payload;

import com.stove.common.event.DomainEvent;
import com.stove.common.event.EventType;
import com.stove.common.event.Topics;
import java.time.Instant;
import java.util.UUID;

public record ReleaseScheduledEvent(String eventId, Instant occurredAt, Long releaseId,
                                    Long submissionId, String productCode, Instant publishAt)
        implements DomainEvent {
    public static ReleaseScheduledEvent of(Long releaseId, Long submissionId,
                                           String productCode, Instant publishAt) {
        return new ReleaseScheduledEvent(UUID.randomUUID().toString(), Instant.now(),
                releaseId, submissionId, productCode, publishAt);
    }
    @Override public String eventType() { return EventType.RELEASE_SCHEDULED; }
    @Override public String topic() { return Topics.STUDIO; }
    @Override public String partitionKey() { return productCode; }
}
