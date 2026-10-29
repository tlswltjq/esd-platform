package com.stove.payment.core.domain;

public record ApprovalPurchase(PgChargeRequest purchase, String supplierAttemptId) {
    public ApprovalPurchase {
        if (purchase == null || !PaymentValues.text(supplierAttemptId, 100))
            throw new IllegalArgumentException("A fixed purchase and supplier attempt are required");
    }
}
