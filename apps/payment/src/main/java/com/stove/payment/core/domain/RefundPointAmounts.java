package com.stove.payment.core.domain;

/** A reported point amount is bounded by the original supplier discount, not inferred from it. */
final class RefundPointAmounts {
    private RefundPointAmounts() { }

    static void requireOriginalLimit(Long reported, PgPaymentAmounts original) {
        if (reported == null) return;
        if (reported < 0 || (original == null && reported != 0)
                || (original != null && reported > original.discountAmount()))
            throw new IllegalArgumentException("Point refund does not match the original approval amounts");
    }
}
