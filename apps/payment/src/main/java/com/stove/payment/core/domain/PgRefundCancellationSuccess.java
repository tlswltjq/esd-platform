package com.stove.payment.core.domain;

import java.time.Instant;

/** A successful response to this exact cancellation request, rather than inferred attribution. */
public record PgRefundCancellationSuccess(PgRefundLookupRequest purchase, String attemptId, RefundCancellation cancellation,
        Instant requestedAt, long taxFreeAmount, long vatAmount, String reason) {
    public PgRefundCancellationSuccess {
        if (purchase == null || !PaymentValues.uuid(attemptId) || cancellation == null || cancellation.getAmount() != purchase.amount()
                || !PaymentValues.microsecond(requestedAt) || requestedAt.isBefore(purchase.paidAt())
                || (cancellation.getCancelledAt() != null && cancellation.getCancelledAt().isBefore(requestedAt))
                || taxFreeAmount < 0 || taxFreeAmount > purchase.amount() || vatAmount < 0 || vatAmount > purchase.amount() - taxFreeAmount
                || !PaymentValues.text(reason, 500)) throw new IllegalArgumentException("Invalid successful cancellation response");
    }
}
