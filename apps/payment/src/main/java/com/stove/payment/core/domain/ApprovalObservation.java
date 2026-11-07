package com.stove.payment.core.domain;

import java.time.Instant;

public record ApprovalObservation(State state, PgResult paid, Instant failedAt) {
    /** CANCELLED is only a verified full cancellation of the held attempt; partial or unresolved cancels are invalid evidence. */
    public enum State { READY, PENDING, PAID, FAILED, CANCELLED }
    public ApprovalObservation {
        if (state == null || (state == State.PAID) != (paid != null)
                || (state == State.FAILED) != (failedAt != null)
                || paid != null && paid.decision() != PgResult.Decision.APPROVED
                || failedAt != null && !PaymentValues.microsecond(failedAt))
            throw new IllegalArgumentException("Invalid approval observation");
    }

    /**
     * Evidence that lets an operator close a held approval (decision 115): the money was returned in full, the PG declined
     * the attempt, or the PG still waits for a confirm that a held approval never sends. PAID and PENDING never close it.
     */
    public boolean closesReview() {
        return state == State.CANCELLED || state == State.FAILED || state == State.READY;
    }
}
