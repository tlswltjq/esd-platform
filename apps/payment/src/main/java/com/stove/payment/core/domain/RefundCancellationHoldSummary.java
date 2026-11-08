package com.stove.payment.core.domain;

import java.time.Instant;

/**
 * The hold columns an administration list needs (PR #168 R5). A formal rejection type can be up to 20,000,000 characters,
 * so a list row carries only its first {@link #PREVIEW_LENGTH} characters and its length; the full type is read only in
 * the detail.
 */
public record RefundCancellationHoldSummary(String holdId, String attemptId, String reason, Integer rejectionStatus,
        String rejectionTypePreview, Integer rejectionTypeLength, Instant observedAt, boolean activated, String resumeRequestId,
        Instant resumedAt, String operatorSubject, String resumeNote) {
    public static final int PREVIEW_LENGTH = 200;

    public static RefundCancellationHoldSummary of(RefundCancellationHold hold) {
        String type = hold.getRejectionType();
        return new RefundCancellationHoldSummary(hold.getHoldId(), hold.getAttemptId(), hold.getReason(), hold.getRejectionStatus(),
                preview(type), type == null ? null : type.codePointCount(0, type.length()), hold.getObservedAt(), hold.isActivated(),
                hold.getResumeRequestId(), hold.getResumedAt(), hold.getOperatorSubject(), hold.getResumeNote());
    }

    public RefundCancellationResumeReceipt receipt() {
        return resumeRequestId == null ? null : new RefundCancellationResumeReceipt(resumeRequestId, attemptId, holdId, resumedAt);
    }

    private static String preview(String type) {
        if (type == null || type.codePointCount(0, type.length()) <= PREVIEW_LENGTH) return type;
        return type.substring(0, type.offsetByCodePoints(0, PREVIEW_LENGTH));
    }
}
