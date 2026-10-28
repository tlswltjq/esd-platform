package com.stove.payment.api.controller.dto;

import com.stove.payment.core.domain.RefundCancellationHoldView;
import com.stove.payment.core.domain.RefundExecutionStatus;
import java.time.Instant;

public record RefundCancellationHoldResponse(String holdId, String attemptId, String refundId, String orderNo,
        String paymentId, long amount, String currency, String reason, Instant observedAt, boolean activated,
        boolean current, boolean resumable, Instant firstPreparedAt, Instant resumeDeadlineAt,
        RefundExecutionStatus executionStatus, Instant refundConfirmedAt, RefundCancellationResumeResponse resume,
        String operatorSubject, String note) {
    public static RefundCancellationHoldResponse from(RefundCancellationHoldView view) {
        return new RefundCancellationHoldResponse(view.holdId(), view.attemptId(), view.refundId(), view.orderNo(),
                view.paymentId(), view.amount(), view.currency(), view.reason(), view.observedAt(), view.activated(), view.current(),
                view.resumable(), view.firstPreparedAt(), view.resumeDeadlineAt(), view.executionStatus(), view.refundConfirmedAt(),
                view.resume() == null ? null : RefundCancellationResumeResponse.from(view.resume()), view.operatorSubject(), view.note());
    }
}
