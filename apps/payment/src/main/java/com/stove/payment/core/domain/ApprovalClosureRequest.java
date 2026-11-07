package com.stove.payment.core.domain;

/** An operator's command to close a held approval. The actor is the authenticated subject, never the request body. */
public record ApprovalClosureRequest(String requestId, String approvalId, String operatorSubject, String note) {
    public static final int NOTE_LIMIT = 500;

    public ApprovalClosureRequest {
        if (!PaymentValues.uuid(requestId) || !PaymentValues.uuid(approvalId)
                || !PaymentValues.text(operatorSubject, 100) || !PaymentValues.text(note, NOTE_LIMIT))
            throw new IllegalArgumentException("A closure must identify its command, approval, operator and reason");
    }
}
