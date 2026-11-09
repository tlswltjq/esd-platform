package com.stove.payment.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import lombok.Getter;

@Embeddable @Getter
public class RefundCancellationSchedule {
    @Column(name = "next_cancel_retry_at") private Instant nextAt;
    @Column(name = "cancel_retry_stage", nullable = false) private int stage;
    @Column(name = "cancel_retry_claim_id", length = 36) private String claimId;
    @Column(name = "cancel_retry_claimed_at") private Instant claimedAt;
    @Column(name = "cancel_retry_lease_until") private Instant leaseUntil;
    @Column(name = "cancel_retry_finished_at") private Instant lastFinishedAt;

    public RefundCancellationClaim claim(String token, Instant at, Duration lease, boolean dueOnly) {
        RefundCancellationClaim candidate = new RefundCancellationClaim(token, at);
        if (lease == null || lease.isNegative() || lease.isZero() || lease.getNano() % 1000 != 0)
            throw new IllegalArgumentException("A positive database-precision lease is required");
        if ((leaseUntil != null && at.isBefore(leaseUntil)) || (dueOnly && (nextAt == null || at.isBefore(nextAt)))) return null;
        // The expired owner's completion is unknown. Its stored fallback already waited its current delay.
        if (claimId != null) stage = Math.min(2, stage + 1);
        claimId = candidate.token(); claimedAt = candidate.startedAt(); leaseUntil = at.plus(lease);
        nextAt = leaseUntil.plusSeconds(delay());
        return candidate;
    }

    public void initializeUnknown(Instant at) {
        requireTime(at);
        if (nextAt != null || claimId != null || lastFinishedAt != null) return;
        // A pre-migration preparation has no observed completion; start a fresh conservative ten-second wait.
        nextAt = at.plusSeconds(10); stage = 1;
    }

    public void finish(RefundCancellationClaim claim, Instant at) {
        requireTime(at);
        if (!owns(claim)) return;
        if (at.isBefore(claim.startedAt())) at = claim.startedAt();
        lastFinishedAt = at; nextAt = at.plusSeconds(delay()); stage = Math.min(2, stage + 1);
        clearClaim();
    }

    public void abandon(RefundCancellationClaim claim) {
        if (!owns(claim)) return;
        nextAt = claim.startedAt(); clearClaim();
    }

    public void stop() { nextAt = null; clearClaim(); }
    public void resume(Instant at) {
        requireTime(at);
        if (nextAt != null || claimId != null) throw new IllegalStateException("Only stopped cancellation work can be resumed");
        nextAt = at;
    }
    public boolean ownsClaim(RefundCancellationClaim claim) { return owns(claim); }

    public boolean ownsAt(RefundCancellationClaim claim, Instant at) {
        requireTime(at);
        return owns(claim) && !at.isBefore(claim.startedAt()) && at.isBefore(leaseUntil);
    }

    private boolean owns(RefundCancellationClaim claim) {
        return nextAt != null && claim != null && Objects.equals(claimId, claim.token()) && Objects.equals(claimedAt, claim.startedAt());
    }
    private long delay() { return stage == 0 ? 10 : stage == 1 ? 30 : 60; }
    private void clearClaim() { claimId = null; claimedAt = null; leaseUntil = null; }
    private static void requireTime(Instant at) {
        if (!PaymentValues.microsecond(at)) throw new IllegalArgumentException("Database-precision time is required");
    }
}
