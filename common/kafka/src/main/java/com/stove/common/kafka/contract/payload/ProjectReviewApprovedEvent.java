package com.stove.common.kafka.contract.payload;

import com.stove.common.kafka.contract.DomainEvent;
import com.stove.common.kafka.contract.Topics;

import java.time.Instant;
import java.util.UUID;

public record ProjectReviewApprovedEvent(
        String eventId,
        Instant occurredAt,
        Long reviewRequestId,
        Long projectId,
        long contentVersion,
        String productCode,
        String actor)
        implements DomainEvent {
    public static ProjectReviewApprovedEvent of(
            Long reviewRequestId,
            Long projectId,
            long contentVersion,
            String productCode,
            String actor) {
        return new ProjectReviewApprovedEvent(
                UUID.randomUUID().toString(),
                Instant.now(),
                reviewRequestId,
                projectId,
                contentVersion,
                productCode,
                actor);
    }

    @Override
    public String eventType() {
        return "ProjectReviewApproved";
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
