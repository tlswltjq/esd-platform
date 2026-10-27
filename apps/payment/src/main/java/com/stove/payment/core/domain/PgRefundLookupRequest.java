package com.stove.payment.core.domain;

import java.time.Instant;

public record PgRefundLookupRequest(String paymentId, String orderNo, long amount, String currency,
        String transactionId, Instant paidAt) {
    public PgRefundLookupRequest {
        if (!PaymentValues.text(paymentId, 64) || !PaymentValues.uuid(orderNo) || amount <= 0
                || !"KRW".equals(currency) || !PaymentValues.text(transactionId, 100)
                || !PaymentValues.microsecond(paidAt)) throw new IllegalArgumentException("Invalid paid purchase evidence");
    }
}
