package com.stove.payment.core.domain;

/** An operator's command to close a delayed or unattributed refund execution. The actor is the authenticated subject. */
public record RefundClosureRequest(String requestId, String attemptId, String operatorSubject, RefundAttribution attribution, String note) {
    public static final int NOTE_LIMIT = 500;

    public RefundClosureRequest {
        if (!PaymentValues.uuid(requestId) || !PaymentValues.uuid(attemptId) || !PaymentValues.text(operatorSubject, 100)
                || attribution == null || !PaymentValues.text(note, NOTE_LIMIT))
            throw new IllegalArgumentException("A closure must identify its command, attempt, operator, judgment and evidence");
    }
}
