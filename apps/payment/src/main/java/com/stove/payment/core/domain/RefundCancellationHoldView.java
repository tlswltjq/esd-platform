package com.stove.payment.core.domain;

import java.time.Instant;

public record RefundCancellationHoldView(String holdId, String attemptId, String refundId, String orderNo,
        String paymentId, long amount, String currency, String reason, Integer rejectionStatus, String rejectionType, Instant observedAt, boolean activated,
        boolean current, boolean resumable, Instant firstPreparedAt, Instant resumeDeadlineAt,
        RefundExecutionStatus executionStatus, Instant refundConfirmedAt, RefundCancellationResumeReceipt resume,
        String operatorSubject, String note) { }
