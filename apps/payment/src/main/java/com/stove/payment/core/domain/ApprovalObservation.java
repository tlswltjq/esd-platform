package com.stove.payment.core.domain;

import java.time.Instant;

public record ApprovalObservation(State state, PgResult paid, Instant failedAt) {
    public enum State { READY, PENDING, PAID, FAILED }
    public ApprovalObservation {
        if (state == null || (state == State.PAID) != (paid != null)
                || (state == State.FAILED) != (failedAt != null)
                || paid != null && paid.decision() != PgResult.Decision.APPROVED
                || failedAt != null && !PaymentValues.microsecond(failedAt))
            throw new IllegalArgumentException("Invalid approval observation");
    }
}
