package com.stove.common.kafka.contract.payload;

import com.stove.common.kafka.contract.DomainEvent;
import com.stove.common.kafka.contract.Topics;

import java.time.Instant;
import java.util.UUID;

public record ProjectReviewRequestedEvent(
        String eventId, Instant occurredAt, Long reviewRequestId, ProjectSnapshot snapshot)
        implements DomainEvent {
    public static ProjectReviewRequestedEvent of(Long reviewRequestId, ProjectSnapshot snapshot) {
        return new ProjectReviewRequestedEvent(
                UUID.randomUUID().toString(), Instant.now(), reviewRequestId, snapshot);
    }

    @Override
    public String eventType() {
        return "ProjectReviewRequested";
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
