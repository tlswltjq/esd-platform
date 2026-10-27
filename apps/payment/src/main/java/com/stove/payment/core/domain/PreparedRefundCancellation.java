package com.stove.payment.core.domain;

/** The committed opaque body is validated by the supplier adapter before transmission. */
public record PreparedRefundCancellation(RefundCancellationPermission permission, String body) {
    public PreparedRefundCancellation {
        if (permission == null || !PaymentValues.text(body, 4096)) throw new IllegalArgumentException("Committed cancellation body is required");
    }
}
