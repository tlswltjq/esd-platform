package com.stove.common.kafka.contract.payload;

import com.stove.common.kafka.contract.DomainEvent;
import com.stove.common.kafka.contract.Topics;

import java.time.Instant;
import java.util.UUID;

public record ProjectReleaseRejectedEvent(
        String eventId,
        Instant occurredAt,
        Long releaseRequestId,
        Long projectId,
        long contentVersion,
        String productCode,
        String reason,
        String requestEventId)
        implements DomainEvent {
    public static ProjectReleaseRejectedEvent of(
            Long releaseRequestId,
            Long projectId,
            long contentVersion,
            String productCode,
            String reason,
            String requestEventId) {
        return new ProjectReleaseRejectedEvent(
                UUID.randomUUID().toString(),
                Instant.now(),
                releaseRequestId,
                projectId,
                contentVersion,
                productCode,
                reason,
                requestEventId);
    }

    @Override
    public String eventType() {
        return "ProjectReleaseRejected";
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
