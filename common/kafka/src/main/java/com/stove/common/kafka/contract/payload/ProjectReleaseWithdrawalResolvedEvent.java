package com.stove.common.kafka.contract.payload;

import com.stove.common.kafka.contract.DomainEvent;
import com.stove.common.kafka.contract.Topics;
import java.time.Instant;
import java.util.UUID;

public record ProjectReleaseWithdrawalResolvedEvent(String eventId, Instant occurredAt, ProjectWithdrawalResult result)
        implements DomainEvent {
    public static ProjectReleaseWithdrawalResolvedEvent of(ProjectWithdrawalResult result) {
        return new ProjectReleaseWithdrawalResolvedEvent(UUID.randomUUID().toString(), Instant.now(), result);
    }

    @Override public String eventType() { return "ProjectReleaseWithdrawalResolved"; }
    @Override public String topic() { return Topics.RELEASE; }
    @Override public String partitionKey() { return result.productCode(); }
}
