package com.stove.payment.core.domain;

import java.time.Instant;
import java.util.UUID;

public record RefundCancellationClaim(String token, Instant startedAt) {
    public RefundCancellationClaim {
        if (token == null || !UUID.fromString(token).toString().equals(token) || !PaymentValues.microsecond(startedAt))
            throw new IllegalArgumentException("A cancellation claim and database-precision start are required");
    }
}
