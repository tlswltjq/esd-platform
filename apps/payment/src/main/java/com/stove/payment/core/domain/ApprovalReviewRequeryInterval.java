package com.stove.payment.core.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * The PG lookup delay of a held ({@code REVIEW_REQUIRED}) approval (decision 122): it grows with the time already held,
 * {@code min(max, max(initial, at - reviewStartedAt))}, so a long hold stops crowding the shared approval queue. A legacy hold
 * without a recorded start uses the cap. The general approval retry delay is separate and unchanged.
 */
public record ApprovalReviewRequeryInterval(Duration initial, Duration max) {
    public ApprovalReviewRequeryInterval {
        if (initial == null || max == null || initial.isNegative() || initial.isZero() || max.compareTo(initial) < 0)
            throw new IllegalArgumentException("A positive held-approval lookup delay not above its cap is required");
    }

    public Duration after(Instant reviewStartedAt, Instant at) {
        if (at == null) throw new IllegalArgumentException("The lookup time is required");
        if (reviewStartedAt == null) return max;
        Duration held = Duration.between(reviewStartedAt, at);
        if (held.compareTo(initial) < 0) return initial;
        return held.compareTo(max) > 0 ? max : held;
    }
}
