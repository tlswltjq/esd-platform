package com.stove.catalog.core.domain;

import com.stove.common.kafka.contract.payload.ProjectWithdrawalRequest;
import java.time.Instant;

public record ReleaseWithdrawalInput(
        String eventId, String eventType, Instant occurredAt, ProjectWithdrawalRequest request) {
    public ReleaseWithdrawalInput {
        ReleaseWithdrawalValues.requireEvent(eventId, eventType, occurredAt);
        ReleaseWithdrawalValues.requireInstruction(request);
    }
}
