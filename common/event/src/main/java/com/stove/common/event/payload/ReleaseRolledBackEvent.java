package com.stove.common.event.payload;

import com.stove.common.event.DomainEvent;
import com.stove.common.event.EventType;
import com.stove.common.event.Topics;
import java.time.Instant;
import java.util.UUID;

public record ReleaseRolledBackEvent(String eventId, Instant occurredAt, Long releaseId,
                                     Long previousReleaseId, Long restoredBuildId, String productCode)
        implements DomainEvent {
    public static ReleaseRolledBackEvent of(Long releaseId, Long previousReleaseId,
                                            Long restoredBuildId, String productCode) {
        return new ReleaseRolledBackEvent(UUID.randomUUID().toString(), Instant.now(), releaseId,
                previousReleaseId, restoredBuildId, productCode);
    }
    @Override public String eventType() { return EventType.RELEASE_ROLLED_BACK; }
    @Override public String topic() { return Topics.STUDIO; }
    @Override public String partitionKey() { return productCode; }
}
