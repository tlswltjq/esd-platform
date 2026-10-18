package com.stove.common.kafka.contract.payload;

import com.stove.common.kafka.contract.DomainEvent;
import com.stove.common.kafka.contract.Topics;
import java.time.Instant;
import java.util.UUID;

/** Completion reported by the external release owner for an immutable Studio request. */
public record ProjectReleaseCompletedEvent(
        String eventId,
        Instant occurredAt,
        Long releaseRequestId,
        Long projectId,
        long contentVersion,
        String productCode,
        String requestEventId)
        implements DomainEvent {
    public static ProjectReleaseCompletedEvent of(
            Long releaseRequestId,
            Long projectId,
            long contentVersion,
            String productCode,
            String requestEventId) {
        return new ProjectReleaseCompletedEvent(
                UUID.randomUUID().toString(),
                Instant.now(),
                releaseRequestId,
                projectId,
                contentVersion,
                productCode,
                requestEventId);
    }

    @Override
    public String eventType() {
        return "ProjectReleaseCompleted";
    }

    @Override
    public String topic() {
        return Topics.RELEASE;
    }

    @Override
    public String partitionKey() {
        return productCode;
    }
}
