package com.stove.payment.core.domain;

public record PgFee(String policyId, int rateBps, long amount) {
    public PgFee {
        if (!PaymentValues.text(policyId, 100) || rateBps < 0 || rateBps > 10000 || amount < 0)
            throw new IllegalArgumentException("Invalid PG fee");
    }
}
