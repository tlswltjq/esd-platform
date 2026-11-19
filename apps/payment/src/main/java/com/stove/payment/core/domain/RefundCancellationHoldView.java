package com.stove.payment.core.domain;

import java.time.Instant;

/** {@code rejectionType} is the full type in a detail view and NULL in a list view, which carries only the preview and length. */
public record RefundCancellationHoldView(String holdId, String attemptId, String refundId, String orderNo,
        String paymentId, long amount, String currency, String reason, Integer rejectionStatus, String rejectionType,
        String rejectionTypePreview, Integer rejectionTypeLength, Instant observedAt, boolean activated,
        boolean current, boolean resumable, Instant firstPreparedAt, Instant resumeDeadlineAt,
        RefundExecutionStatus executionStatus, Instant refundConfirmedAt, RefundCancellationResumeReceipt resume,
        String operatorSubject, String note) { }
