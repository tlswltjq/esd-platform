package com.stove.payment.core.domain;

import java.time.Instant;
import java.util.UUID;

public record RefundLookupClaim(String token, Instant startedAt) {
    public RefundLookupClaim {
        if (token == null || !UUID.fromString(token).toString().equals(token) || !PaymentValues.microsecond(startedAt))
            throw new IllegalArgumentException("A durable lookup claim is required");
    }
}
