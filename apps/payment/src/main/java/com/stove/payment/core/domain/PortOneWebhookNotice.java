package com.stove.payment.core.domain;

import java.time.Instant;

/** Validated identifiers for a recheck hint; this is not a supplier payment decision. */
public record PortOneWebhookNotice(String messageId, String payloadHash, String type, String storeId,
        String paymentId, String transactionId, Instant occurredAt) {
    public PortOneWebhookNotice {
        if (messageId == null || !messageId.matches("[A-Za-z0-9_-]{1,200}")
                || payloadHash == null || !payloadHash.matches("[a-f0-9]{64}")
                || !PaymentValues.text(type, 100) || !type.startsWith("Transaction.")
                || !PaymentValues.text(storeId, 64) || !PaymentValues.text(paymentId, 64)
                || !PaymentValues.text(transactionId, 100) || !PaymentValues.microsecond(occurredAt)) {
            throw new IllegalArgumentException("Invalid payment recheck notice");
        }
    }
}
