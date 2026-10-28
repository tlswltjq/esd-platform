package com.stove.payment.core.domain;

import java.time.Instant;

/** Stored facts as of this read; schedule fields do not prove a successful supplier lookup. */
public record RefundDelayView(String attemptId, String refundId, String orderNo, String paymentId,
        long amount, String currency, Instant receivedAt, Instant firstPreparedAt, Instant delayThresholdAt,
        Instant asOf, boolean currentlyDelayed, RefundExecutionStatus executionStatus,
        Instant refundConfirmedAt, String refundConfirmationSource, boolean reconciliationRequired, Instant failureConfirmedAt,
        Instant automaticCancellationStoppedAt, Instant cancellationBalanceChangedAt, String activeCancellationHoldId,
        String activeCancellationHoldReason, PgRefundCancellationReceipt.State receiptState, Instant receiptObservedAt,
        Instant nextLookupAt, Instant lookupWakeAt, Instant lookupLeaseUntil,
        Instant nextCancellationRetryAt, Instant cancellationLeaseUntil) { }
