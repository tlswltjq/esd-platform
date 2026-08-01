package com.stove.review.core.domain;

import java.util.Set;

/**
 * 심의 상태머신.
 * <pre>
 * REQUESTED ──(접수/배정)──▶ IN_REVIEW ──▶ APPROVED
 *                                    └──▶ REJECTED ──(재신청)──▶ REQUESTED
 * </pre>
 */
public enum ReviewStatus {
    REQUESTED,
    IN_REVIEW,
    APPROVED,
    REJECTED;

    private static final Set<ReviewStatus> FROM_REQUESTED = Set.of(IN_REVIEW);
    private static final Set<ReviewStatus> FROM_IN_REVIEW = Set.of(APPROVED, REJECTED);
    private static final Set<ReviewStatus> FROM_REJECTED = Set.of(REQUESTED);

    public boolean canTransitTo(ReviewStatus next) {
        return switch (this) {
            case REQUESTED -> FROM_REQUESTED.contains(next);
            case IN_REVIEW -> FROM_IN_REVIEW.contains(next);
            case REJECTED -> FROM_REJECTED.contains(next);
            case APPROVED -> false;
        };
    }
}
