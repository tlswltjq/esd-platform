package com.stove.payment.core.domain;

import java.time.Instant;

public record PgRefundLookupRequest(String paymentId, String orderNo, long amount, String currency,
        String transactionId, Instant paidAt, PgPaymentAmounts paymentAmounts) {
    public PgRefundLookupRequest {
        if (!PaymentValues.text(paymentId, 64) || !PaymentValues.uuid(orderNo) || amount <= 0
                || !"KRW".equals(currency) || !PaymentValues.text(transactionId, 100)
                || !PaymentValues.microsecond(paidAt)) throw new IllegalArgumentException("Invalid paid purchase evidence");
        if (paymentAmounts != null) paymentAmounts.requireTotal(amount);
    }

    public PgRefundLookupRequest(String paymentId, String orderNo, long amount, String currency,
            String transactionId, Instant paidAt) {
        this(paymentId, orderNo, amount, currency, transactionId, paidAt, null);
    }

    public static PgRefundLookupRequest from(Payment payment) {
        if (payment == null) throw new IllegalArgumentException("Original payment is required");
        PgPaymentAmounts amounts = payment.getPgPaidAmount() == null ? null
                : new PgPaymentAmounts(payment.getPgPaidAmount(), payment.getPgDiscountAmount());
        return new PgRefundLookupRequest(payment.getPaymentId(), payment.getOrderNo(), payment.getAmount(), payment.getCurrency(),
                payment.getProviderTransactionId(), payment.getProviderOccurredAt(), amounts);
    }
}
