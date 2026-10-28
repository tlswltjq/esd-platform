package com.stove.payment.core.domain;

public record RefundInvestigationRequest(String requestId, String attemptId, String operatorSubject, String note) {
    public RefundInvestigationRequest {
        if (!PaymentValues.uuid(requestId) || !PaymentValues.uuid(attemptId)
                || !PaymentValues.text(operatorSubject, 100) || !PaymentValues.text(note, 500))
            throw new IllegalArgumentException("An investigation must identify its command, execution, operator and findings");
    }
}
