package com.stove.payment.core.domain;

import java.time.Instant;

/** A confirmed supplier decision. An unavailable or unresolved call has no final result. */
public record PgResult(String provider, String paymentId, String orderNo, long amount, String currency,
        String transactionId, Instant occurredAt, Decision decision, String reasonCode, String reason, PgFee fee) {
    public enum Decision { APPROVED, DECLINED }
    /** Existing supplier decisions may have an unknown fee. */
    public PgResult(String provider, String paymentId, String orderNo, long amount, String currency,
            String transactionId, Instant occurredAt, Decision decision, String reasonCode, String reason) {
        this(provider, paymentId, orderNo, amount, currency, transactionId, occurredAt, decision, reasonCode, reason, null);
    }
    public PgResult {
        new PgChargeRequest(paymentId,orderNo,amount,currency);
        if (!PaymentValues.text(provider,30) || !PaymentValues.text(transactionId,100)
                || !PaymentValues.microsecond(occurredAt) || decision == null
                || (decision == Decision.APPROVED && (reasonCode != null || reason != null))
                || (decision == Decision.DECLINED && (!PaymentValues.text(reasonCode,100) || !PaymentValues.text(reason,500)))
                || (fee != null && (fee.amount() > amount || decision == Decision.DECLINED && fee.amount() != 0)))
            throw new IllegalArgumentException("Invalid confirmed PG result");
    }
    public void requireRequest(PgChargeRequest request) {
        if (!paymentId.equals(request.paymentId()) || !orderNo.equals(request.orderNo())
                || amount != request.amount() || !currency.equals(request.currency()))
            throw new IllegalArgumentException("PG result does not match the charge request");
    }
}
