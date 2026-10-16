package com.stove.payment.core.domain;

public record PgChargeRequest(String paymentId, String orderNo, long amount, String currency) {
    public PgChargeRequest {
        if (!PaymentValues.text(paymentId,64) || !PaymentValues.uuid(orderNo)
                || amount <= 0 || !"KRW".equals(currency)) throw new IllegalArgumentException("Invalid PG charge request");
    }
}
