package com.stove.payment.core.domain;

public record RefundCancellationResumeRequest(String requestId, String attemptId, String holdId, String operatorSubject, String note) {
    public RefundCancellationResumeRequest {
        if (!PaymentValues.uuid(requestId) || !PaymentValues.uuid(attemptId) || !PaymentValues.uuid(holdId)
                || !PaymentValues.text(operatorSubject, 100) || !PaymentValues.text(note, 500))
            throw new IllegalArgumentException("A resume command must identify its execution, hold, operator and review note");
    }
}
