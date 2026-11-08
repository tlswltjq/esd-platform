package com.stove.payment.core.domain;

/** An operator's command to close a stopped refund execution as failed (decision 128). The actor is the authenticated subject. */
public record RefundFailureClosureRequest(String requestId, String attemptId, String operatorSubject, String note) {
    public static final int NOTE_LIMIT = 500;

    public RefundFailureClosureRequest {
        if (!PaymentValues.uuid(requestId) || !PaymentValues.uuid(attemptId) || !PaymentValues.text(operatorSubject, 100)
                || !PaymentValues.text(note, NOTE_LIMIT))
            throw new IllegalArgumentException("A failure closure must identify its command, attempt, operator and evidence");
    }
}
