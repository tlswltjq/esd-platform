package com.stove.payment.core.domain;

import java.time.Instant;

/** A validated response ties a supplier cancellation ID to this exact local attempt. */
public record PgRefundCancellationReceipt(PgRefundLookupRequest purchase, String attemptId, String cancellationId,
        State state, Instant requestedAt, long taxFreeAmount, long vatAmount, String reason) {
    public PgRefundCancellationReceipt {
        if (purchase == null || !PaymentValues.uuid(attemptId) || !PaymentValues.text(cancellationId, 100) || state == null
                || !PaymentValues.microsecond(requestedAt) || requestedAt.isBefore(purchase.paidAt())
                || taxFreeAmount < 0 || taxFreeAmount > purchase.amount() || vatAmount < 0 || vatAmount > purchase.amount() - taxFreeAmount
                || !PaymentValues.text(reason, 500)) throw new IllegalArgumentException("Invalid cancellation receipt");
    }
    public enum State { REQUESTED, FAILED }
    public PgRefundCancellationReceipt failed() {
        return new PgRefundCancellationReceipt(purchase, attemptId, cancellationId, State.FAILED, requestedAt, taxFreeAmount, vatAmount, reason);
    }
}
