package com.stove.payment.core.domain;

/** Supplier-reported customer payment and discount, separate from the immutable order amount and PG fee. */
public record PgPaymentAmounts(long paidAmount, long discountAmount) {
    public PgPaymentAmounts {
        if (paidAmount < 0 || discountAmount < 0) throw new IllegalArgumentException("Invalid supplier payment amounts");
    }

    public void requireTotal(long total) {
        if (total <= 0 || paidAmount > total || discountAmount != total - paidAmount)
            throw new IllegalArgumentException("Supplier payment amounts do not match the order total");
    }
}
