package com.stove.payment.core.domain;

import java.time.Instant;

/** Server-verified failure of one supplier attempt, never a final decline of the payment. */
public record PgFailedPaymentAttempt(PgChargeRequest purchase, String supplierAttemptId, Instant requestedAt, Instant failedAt) {
    public PgFailedPaymentAttempt {
        if (purchase == null || !PaymentValues.text(supplierAttemptId, 100) || !PaymentValues.microsecond(requestedAt)
                || !PaymentValues.microsecond(failedAt) || failedAt.isBefore(requestedAt))
            throw new IllegalArgumentException("A failed supplier attempt needs its purchase, identity and ordered timestamps");
    }
}
