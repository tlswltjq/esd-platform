package com.stove.payment.api.controller.dto;

import com.stove.payment.core.domain.PgRefundCancellationReceipt;
import com.stove.payment.core.domain.RefundDelayView;
import com.stove.payment.core.domain.RefundExecutionStatus;
import java.time.Instant;

public record RefundDelayResponse(String attemptId, String refundId, String orderNo, String paymentId,
        long amount, String currency, Instant receivedAt, Instant firstPreparedAt, Instant delayThresholdAt,
        Instant asOf, boolean currentlyDelayed, RefundExecutionStatus executionStatus,
        Instant refundConfirmedAt, String refundConfirmationSource, boolean reconciliationRequired, Instant failureConfirmedAt,
        Instant automaticCancellationStoppedAt, Instant cancellationBalanceChangedAt, String activeCancellationHoldId,
        String activeCancellationHoldReason, PgRefundCancellationReceipt.State receiptState, Instant receiptObservedAt,
        Instant nextLookupAt, Instant lookupWakeAt, Instant lookupLeaseUntil,
        Instant nextCancellationRetryAt, Instant cancellationLeaseUntil) {
    public static RefundDelayResponse from(RefundDelayView view) {
        return new RefundDelayResponse(view.attemptId(), view.refundId(), view.orderNo(), view.paymentId(), view.amount(), view.currency(),
                view.receivedAt(), view.firstPreparedAt(), view.delayThresholdAt(), view.asOf(), view.currentlyDelayed(), view.executionStatus(),
                view.refundConfirmedAt(), view.refundConfirmationSource(), view.reconciliationRequired(), view.failureConfirmedAt(),
                view.automaticCancellationStoppedAt(), view.cancellationBalanceChangedAt(), view.activeCancellationHoldId(),
                view.activeCancellationHoldReason(), view.receiptState(), view.receiptObservedAt(), view.nextLookupAt(), view.lookupWakeAt(),
                view.lookupLeaseUntil(), view.nextCancellationRetryAt(), view.cancellationLeaseUntil());
    }
}
