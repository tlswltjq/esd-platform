package com.stove.payment.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * One full-amount FAILED cancellation the PG listed when an operator closed a refund as failed (decision 128). The next
 * attempt's preflight expects exactly these rows, as it expects the failed receipts of PG-confirmed failures.
 */
@Embeddable @Getter @EqualsAndHashCode
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefundFailedCancellation {
    @Column(name = "cancellation_id", nullable = false, length = 100) private String cancellationId;
    @Column(name = "requested_at", nullable = false) private Instant requestedAt;
    @Column(name = "tax_free_amount", nullable = false) private long taxFreeAmount;
    @Column(name = "vat_amount", nullable = false) private long vatAmount;
    @Column(name = "reason", nullable = false, length = 500) private String reason;
    @Column(name = "easy_pay_discount_amount") private Long easyPayDiscountAmount;

    public RefundFailedCancellation(String cancellationId, Instant requestedAt, long taxFreeAmount, long vatAmount, String reason,
            Long easyPayDiscountAmount) {
        if (!PaymentValues.text(cancellationId, 100) || !PaymentValues.microsecond(requestedAt) || taxFreeAmount < 0 || vatAmount < 0
                || !PaymentValues.text(reason, 500) || (easyPayDiscountAmount != null && easyPayDiscountAmount < 0))
            throw new IllegalArgumentException("Invalid failed cancellation evidence");
        this.cancellationId = cancellationId; this.requestedAt = requestedAt; this.taxFreeAmount = taxFreeAmount;
        this.vatAmount = vatAmount; this.reason = reason; this.easyPayDiscountAmount = easyPayDiscountAmount;
    }

    /** The expected preflight row, attributed to the attempt that was closed (the PG does not say which attempt sent it). */
    public PgRefundCancellationReceipt receipt(PgRefundLookupRequest purchase, String closedAttemptId) {
        return new PgRefundCancellationReceipt(purchase, closedAttemptId, cancellationId, PgRefundCancellationReceipt.State.FAILED,
                requestedAt, taxFreeAmount, vatAmount, reason, easyPayDiscountAmount);
    }
}
