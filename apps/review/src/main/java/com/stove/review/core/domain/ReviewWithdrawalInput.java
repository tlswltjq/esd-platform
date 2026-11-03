package com.stove.review.core.domain;

import com.stove.common.kafka.contract.payload.ProjectWithdrawalRequest;
import java.time.Instant;

public record ReviewWithdrawalInput(
        String eventId, String eventType, Instant occurredAt, ProjectWithdrawalRequest request) {
    public ReviewWithdrawalInput {
        ReviewWithdrawalValues.requireEvent(eventId, eventType, occurredAt);
        ReviewWithdrawalValues.requireInstruction(request);
    }
}
