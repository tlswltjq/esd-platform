package com.stove.common.kafka.contract.payload;

import com.stove.common.kafka.contract.DomainEvent;
import com.stove.common.kafka.contract.Topics;
import java.time.Instant;
import java.util.UUID;

public record ProjectReviewWithdrawalRequestedEvent(String eventId, Instant occurredAt, ProjectWithdrawalRequest request)
        implements DomainEvent {
    public static ProjectReviewWithdrawalRequestedEvent of(ProjectWithdrawalRequest request) {
        return new ProjectReviewWithdrawalRequestedEvent(UUID.randomUUID().toString(), Instant.now(), request);
    }

    @Override public String eventType() { return "ProjectReviewWithdrawalRequested"; }
    @Override public String topic() { return Topics.STUDIO; }
    @Override public String partitionKey() { return request.productCode(); }
}
