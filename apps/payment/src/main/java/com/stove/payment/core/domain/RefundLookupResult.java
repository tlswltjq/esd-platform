package com.stove.payment.core.domain;

public record RefundLookupResult(PgRefundConfirmation confirmation, PgRefundFailureConfirmation failure) {
    public RefundLookupResult {
        if (confirmation != null && failure != null) throw new IllegalArgumentException("Conflicting lookup decisions");
    }
}
