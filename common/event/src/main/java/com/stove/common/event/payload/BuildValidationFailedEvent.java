package com.stove.common.event.payload;

import com.stove.common.event.DomainEvent;
import com.stove.common.event.EventType;
import com.stove.common.event.Topics;
import java.time.Instant;
import java.util.UUID;

public record BuildValidationFailedEvent(
        String eventId,
        Instant occurredAt,
        Long buildId,
        Long gameId,
        String productCode,
        String failureCode
) implements DomainEvent {

    public static BuildValidationFailedEvent of(Long buildId, Long gameId, String productCode,
                                                String failureCode) {
        return new BuildValidationFailedEvent(UUID.randomUUID().toString(), Instant.now(),
                buildId, gameId, productCode, failureCode);
    }

    @Override public String eventType() { return EventType.BUILD_VALIDATION_FAILED; }
    @Override public String topic() { return Topics.STUDIO; }
    @Override public String partitionKey() { return productCode; }
}
