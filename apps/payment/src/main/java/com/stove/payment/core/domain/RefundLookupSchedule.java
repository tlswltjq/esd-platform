package com.stove.payment.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import lombok.Getter;

/** Durable read-only deadlines. A webhook hint does not reset the periodic backoff. */
@Embeddable @Getter
public class RefundLookupSchedule {
    @Column(name = "next_refund_lookup_at") private Instant nextAt;
    @Column(name = "refund_lookup_stage", nullable = false) private int stage;
    @Column(name = "refund_lookup_claim_id", length = 36) private String claimId;
    @Column(name = "refund_lookup_lease_until") private Instant leaseUntil;
    @Column(name = "refund_lookup_wake_at") private Instant wakeAt;

    public void prepare(Instant firstPreparedAt) {
        requireTime(firstPreparedAt);
        if (nextAt == null) nextAt = firstPreparedAt.plusSeconds(5);
    }

    public RefundLookupClaim claim(String token, Instant at, Duration lease) {
        requireTime(at);
        if (lease == null || lease.isNegative() || lease.isZero() || lease.getNano() % 1000 != 0)
            throw new IllegalArgumentException("A positive database-precision lease is required");
        if (nextAt == null || (at.isBefore(nextAt) && (wakeAt == null || at.isBefore(wakeAt)))
                || (leaseUntil != null && at.isBefore(leaseUntil))) return null;
        RefundLookupClaim claim = new RefundLookupClaim(token, at);
        claimId = token; leaseUntil = at.plus(lease); wakeAt = null;
        return claim;
    }

    public void finish(RefundLookupClaim claim, Instant firstPreparedAt, Instant at) {
        requireTime(at); requireTime(firstPreparedAt);
        if (!owns(claim)) return;
        if (at.isBefore(claim.startedAt())) {
            // A backwards clock must not create a tighter catch-up loop.
            at = claim.startedAt();
        }
        if (!at.isBefore(nextAt)) {
            long age = Duration.between(firstPreparedAt, at).getSeconds();
            int elapsedStage = age >= 35 ? 3 : age >= 15 ? 2 : 1;
            stage = Math.min(3, Math.max(stage + 1, elapsedStage));
            long delay = stage == 1 ? 10 : stage == 2 ? 20 : 60;
            Instant target = claim.startedAt().plusSeconds(delay);
            nextAt = target.isAfter(at) ? target : at.plusSeconds(delay);
        }
        claimId = null; leaseUntil = null;
    }

    public void abandon(RefundLookupClaim claim) {
        if (!owns(claim)) return;
        wake(claim.startedAt());
        claimId = null; leaseUntil = null;
    }

    public void wake(Instant at) {
        requireTime(at);
        if (nextAt != null && (wakeAt == null || at.isBefore(wakeAt))) wakeAt = at;
    }

    public void stop() { nextAt = null; claimId = null; leaseUntil = null; wakeAt = null; }

    /**
     * The operator closure (decision 127) takes the worker's lease without waiting for {@code nextAt}. It refuses while any
     * other lease is live. A stopped schedule has no worker and the database keeps no lease without a lookup deadline
     * ({@code ck_refund_lookup_lease}), so there is nothing to take.
     */
    public boolean claimForClosure(String token, Instant at, Duration lease) {
        requireTime(at);
        if (!PaymentValues.uuid(token) || lease == null || lease.isNegative() || lease.isZero() || lease.getNano() % 1000 != 0)
            throw new IllegalArgumentException("A closure token and positive database-precision lease are required");
        if (nextAt == null) throw new IllegalStateException("A stopped lookup schedule has no worker lease");
        if (leaseUntil != null && at.isBefore(leaseUntil)) return false;
        claimId = token; leaseUntil = at.plus(lease);
        return true;
    }

    public boolean ownsClosure(String token, Instant at) {
        requireTime(at);
        return token != null && token.equals(claimId) && leaseUntil != null && at.isBefore(leaseUntil);
    }

    /** Returns the lease; the worker's own deadline and wake hint stay as they were. A lease that is not ours is left alone. */
    public void releaseClosure(String token) {
        if (token != null && token.equals(claimId)) { claimId = null; leaseUntil = null; }
    }

    private boolean owns(RefundLookupClaim claim) {
        return nextAt != null && claim != null && Objects.equals(claimId, claim.token());
    }

    private static void requireTime(Instant at) {
        if (!PaymentValues.microsecond(at)) throw new IllegalArgumentException("Database precision time is required");
    }
}
