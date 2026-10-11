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
    /** 심사 요청을 접수한 상태. */
    REQUESTED,
    /** 담당자가 심사를 진행 중인 상태. */
    IN_REVIEW,
    /** 심사 또는 승인 처리가 완료된 상태. */
    APPROVED,
    /** 심사에서 반려된 상태. */
    REJECTED;

    /** 접수 상태에서 전이할 수 있는 심사 상태 집합. */
    private static final Set<ReviewStatus> FROM_REQUESTED = Set.of(IN_REVIEW);
    /** 심사 중 상태에서 전이할 수 있는 심사 상태 집합. */
    private static final Set<ReviewStatus> FROM_IN_REVIEW = Set.of(APPROVED, REJECTED);
    /** 반려 상태에서 전이할 수 있는 심사 상태 집합. */
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
