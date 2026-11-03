package com.stove.common.kafka.contract.payload;

import com.stove.common.kafka.contract.DomainEvent;
import com.stove.common.kafka.contract.Topics;
import java.time.Instant;
import java.util.UUID;

public record ProjectReleaseWithdrawalRequestedEvent(String eventId, Instant occurredAt, ProjectWithdrawalRequest request)
        implements DomainEvent {
    public static ProjectReleaseWithdrawalRequestedEvent of(ProjectWithdrawalRequest request) {
        return new ProjectReleaseWithdrawalRequestedEvent(UUID.randomUUID().toString(), Instant.now(), request);
    }

    @Override public String eventType() { return "ProjectReleaseWithdrawalRequested"; }
    @Override public String topic() { return Topics.STUDIO; }
    @Override public String partitionKey() { return request.productCode(); }
}
