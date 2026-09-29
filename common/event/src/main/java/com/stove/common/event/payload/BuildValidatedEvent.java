package com.stove.common.event.payload;

import com.stove.common.event.DomainEvent;
import com.stove.common.event.EventType;
import com.stove.common.event.Topics;
import java.time.Instant;
import java.util.UUID;

public record BuildValidatedEvent(
        String eventId,
        Instant occurredAt,
        Long buildId,
        Long gameId,
        String productCode,
        String checksum,
        String platform,
        String productVersion,
        String buildNumber,
        String commitSha
) implements DomainEvent {

    public static BuildValidatedEvent of(Long buildId, Long gameId, String productCode,
                                         String checksum, String platform, String productVersion,
                                         String buildNumber, String commitSha) {
        return new BuildValidatedEvent(UUID.randomUUID().toString(), Instant.now(), buildId, gameId,
                productCode, checksum, platform, productVersion, buildNumber, commitSha);
    }

    @Override public String eventType() { return EventType.BUILD_VALIDATED; }
    @Override public String topic() { return Topics.STUDIO; }
    @Override public String partitionKey() { return productCode; }
}
