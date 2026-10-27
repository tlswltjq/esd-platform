package com.stove.payment.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** One successful supplier cancellation; it does not identify our execution attempt. */
@Embeddable @Getter @EqualsAndHashCode
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefundCancellation {
    @Column(name = "cancellation_id", nullable = false, length = 100)
    private String cancellationId;
    @Column(nullable = false)
    private long amount;
    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    public RefundCancellation(String cancellationId, long amount, Instant cancelledAt) {
        if (!PaymentValues.text(cancellationId, 100) || amount <= 0
                || (cancelledAt != null && !PaymentValues.microsecond(cancelledAt)))
            throw new IllegalArgumentException("Invalid successful cancellation evidence");
        this.cancellationId = cancellationId;
        this.amount = amount;
        this.cancelledAt = cancelledAt;
    }
}
