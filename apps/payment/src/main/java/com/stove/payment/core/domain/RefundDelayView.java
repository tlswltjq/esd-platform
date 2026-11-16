package com.stove.payment.core.domain;

import java.time.Instant;

/**
 * Stored facts as of this read; schedule fields do not prove a successful supplier lookup. {@code reconciliationRequired}
 * is an open unattributed execution: a PG response or an operator closure (decision 127) ends it. {@code lateCancellationObservedAt}
 * is a full cancellation found after the failure (decision 128); it is for investigation and changes nothing else.
 */
public record RefundDelayView(String attemptId, String refundId, String orderNo, String paymentId,
        long amount, String currency, Instant receivedAt, Instant firstPreparedAt, Instant delayThresholdAt,
        Instant asOf, boolean currentlyDelayed, RefundExecutionStatus executionStatus,
        Instant refundConfirmedAt, String refundConfirmationSource, boolean reconciliationRequired, Instant failureConfirmedAt,
        Instant automaticCancellationStoppedAt, Instant cancellationBalanceChangedAt, String activeCancellationHoldId,
        String activeCancellationHoldReason, PgRefundCancellationReceipt.State receiptState, Instant receiptObservedAt,
        Instant nextLookupAt, Instant lookupWakeAt, Instant lookupLeaseUntil,
        Instant nextCancellationRetryAt, Instant cancellationLeaseUntil, RefundClosureView closure,
        RefundFailureClosureView failureClosure, Instant lateCancellationObservedAt, Instant lateCancelledAt) { }
