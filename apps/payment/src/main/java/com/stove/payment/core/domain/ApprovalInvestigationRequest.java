package com.stove.payment.core.domain;

public record ApprovalInvestigationRequest(String requestId, String approvalId, String operatorSubject, String note) {
    public ApprovalInvestigationRequest {
        if (!PaymentValues.uuid(requestId) || !PaymentValues.uuid(approvalId)
                || !PaymentValues.text(operatorSubject, 100) || !PaymentValues.text(note, 500))
            throw new IllegalArgumentException("An investigation must identify its command, execution, operator and findings");
    }
}
