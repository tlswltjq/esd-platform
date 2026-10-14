package com.stove.common.kafka.contract.payload;

import com.stove.common.kafka.contract.DomainEvent;
import com.stove.common.kafka.contract.Topics;

import java.time.Instant;
import java.util.UUID;

public record ProjectReviewRejectedEvent(
        String eventId,
        Instant occurredAt,
        Long reviewRequestId,
        Long projectId,
        long contentVersion,
        String productCode,
        String feedback,
        String actor)
        implements DomainEvent {
    public static ProjectReviewRejectedEvent of(
            Long reviewRequestId,
            Long projectId,
            long contentVersion,
            String productCode,
            String feedback,
            String actor) {
        return new ProjectReviewRejectedEvent(
                UUID.randomUUID().toString(),
                Instant.now(),
                reviewRequestId,
                projectId,
                contentVersion,
                productCode,
                feedback,
                actor);
    }

    @Override
    public String eventType() {
        return "ProjectReviewRejected";
    }

    @Override
    public String topic() {
        return Topics.REVIEW;
    }

    @Override
    public String partitionKey() {
        return productCode;
    }
}
