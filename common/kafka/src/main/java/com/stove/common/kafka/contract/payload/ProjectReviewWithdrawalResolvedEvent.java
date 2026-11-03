package com.stove.common.kafka.contract.payload;

import com.stove.common.kafka.contract.DomainEvent;
import com.stove.common.kafka.contract.Topics;
import java.time.Instant;
import java.util.UUID;

public record ProjectReviewWithdrawalResolvedEvent(String eventId, Instant occurredAt, ProjectWithdrawalResult result)
        implements DomainEvent {
    public static ProjectReviewWithdrawalResolvedEvent of(ProjectWithdrawalResult result) {
        return new ProjectReviewWithdrawalResolvedEvent(UUID.randomUUID().toString(), Instant.now(), result);
    }

    @Override public String eventType() { return "ProjectReviewWithdrawalResolved"; }
    @Override public String topic() { return Topics.REVIEW; }
    @Override public String partitionKey() { return result.productCode(); }
}
