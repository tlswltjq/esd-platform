package com.stove.common.kafka.contract.payload;

import com.stove.common.kafka.contract.DomainEvent;
import com.stove.common.kafka.contract.Topics;

import java.time.Instant;
import java.util.UUID;

public record ProjectReleaseRequestedEvent(
        String eventId,
        Instant occurredAt,
        Long releaseRequestId,
        Long reviewRequestId,
        ProjectSnapshot snapshot)
        implements DomainEvent {
    public static ProjectReleaseRequestedEvent of(
            Long releaseRequestId, Long reviewRequestId, ProjectSnapshot snapshot) {
        return new ProjectReleaseRequestedEvent(
                UUID.randomUUID().toString(),
                Instant.now(),
                releaseRequestId,
                reviewRequestId,
                snapshot);
    }

    @Override
    public String eventType() {
        return "ProjectReleaseRequested";
    }

    @Override
    public String topic() {
        return Topics.STUDIO;
    }

    @Override
    public String partitionKey() {
        return snapshot.productCode();
    }
}
