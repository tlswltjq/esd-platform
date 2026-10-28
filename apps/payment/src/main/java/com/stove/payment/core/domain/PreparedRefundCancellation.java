package com.stove.payment.core.domain;

/** The committed opaque body is validated by the supplier adapter before transmission. */
public record PreparedRefundCancellation(RefundCancellationPermission permission, String body, RefundCancellationClaim claim) {
    public PreparedRefundCancellation {
        if (permission == null || !PaymentValues.text(body, 4096) || claim == null)
            throw new IllegalArgumentException("Committed cancellation body and owner are required");
    }
}
