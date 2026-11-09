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
    /**
     * The operator failure closure's mark (V40, PR #166 R1). While it is live the closure is re-inspecting the PG, and no
     * dispatch claim — first send or retry — and no operator resume may start: a POST after the GET could move money the
     * observation does not show. It lasts as long as the closure's lookup lease and simply expires if the closure dies.
     */
    @Column(name = "cancel_closure_id", length = 36) private String closureId;
    @Column(name = "cancel_closure_until") private Instant closureUntil;

    public RefundCancellationClaim claim(String token, Instant at, Duration lease, boolean dueOnly) {
        RefundCancellationClaim candidate = new RefundCancellationClaim(token, at);
        if (lease == null || lease.isNegative() || lease.isZero() || lease.getNano() % 1000 != 0)
            throw new IllegalArgumentException("A positive database-precision lease is required");
        if ((leaseUntil != null && at.isBefore(leaseUntil)) || (dueOnly && (nextAt == null || at.isBefore(nextAt)))) return null;
        // A failure closure is re-inspecting the PG (PR #166 R1): no POST may follow its GET.
        if (closureMarkedAt(at)) return null;
        // The expired owner's completion is unknown. Its stored fallback already waited its current delay.
        if (claimId != null) stage = Math.min(2, stage + 1);
        claimId = candidate.token(); claimedAt = candidate.startedAt(); leaseUntil = at.plus(lease);
        nextAt = leaseUntil.plusSeconds(delay());
        return candidate;
    }

    /** Marks the dispatch side for a failure closure. Refuses while another closure's mark is live; replaces an expired one. */
    public boolean markClosure(String token, Instant at, Duration lease) {
        requireTime(at);
        if (!PaymentValues.uuid(token) || lease == null || lease.isNegative() || lease.isZero() || lease.getNano() % 1000 != 0)
            throw new IllegalArgumentException("A closure token and positive database-precision lease are required");
        if (closureMarkedAt(at)) return false;
        closureId = token; closureUntil = at.plus(lease);
        return true;
    }

    public boolean closureMarkedAt(Instant at) {
        requireTime(at);
        return closureId != null && closureUntil != null && at.isBefore(closureUntil);
    }

    public boolean ownsClosure(String token, Instant at) {
        return token != null && token.equals(closureId) && closureMarkedAt(at);
    }

    /** Only the closure that set the mark clears it; a successor's mark is left alone. */
    public void releaseClosure(String token) {
        if (token != null && token.equals(closureId)) { closureId = null; closureUntil = null; }
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
