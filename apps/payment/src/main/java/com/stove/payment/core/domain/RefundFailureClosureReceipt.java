package com.stove.payment.core.domain;

import java.time.Instant;

/** The failure closure result. Every value comes from the closure record, so a replay returns the first response unchanged. */
public record RefundFailureClosureReceipt(RefundFailureClosureView closure, String refundId, String orderNo, String paymentId,
        RefundExecutionStatus executionStatus, Instant failureConfirmedAt) { }
