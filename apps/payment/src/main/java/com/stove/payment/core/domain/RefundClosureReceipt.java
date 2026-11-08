package com.stove.payment.core.domain;

import java.time.Instant;

/** The closure result. The financial confirmation it reports is the first one and never changes after the closure. */
public record RefundClosureReceipt(RefundClosureView closure, String refundId, String orderNo, String paymentId,
        RefundExecutionStatus executionStatus, Instant refundConfirmedAt, String refundConfirmationSource) { }
