package com.stove.payment.api.controller.dto;

import com.stove.payment.core.domain.RefundCancellationHoldView;
import com.stove.payment.core.domain.RefundExecutionStatus;
import java.time.Instant;

/**
 * A row of the administration hold list. Same fields as the detail except the formal rejection type: the list shows its
 * first 200 characters and its length in characters, and the full type is only in the detail (PR #168 R5).
 */
public record RefundCancellationHoldListItem(String holdId, String attemptId, String refundId, String orderNo,
        String paymentId, long amount, String currency, String reason, Integer rejectionStatus, String rejectionTypePreview,
        Integer rejectionTypeLength, Instant observedAt, boolean activated, boolean current, boolean resumable, Instant firstPreparedAt,
        Instant resumeDeadlineAt, RefundExecutionStatus executionStatus, Instant refundConfirmedAt, RefundCancellationResumeResponse resume,
        String operatorSubject, String note) {
    public static RefundCancellationHoldListItem from(RefundCancellationHoldView view) {
        return new RefundCancellationHoldListItem(view.holdId(), view.attemptId(), view.refundId(), view.orderNo(),
                view.paymentId(), view.amount(), view.currency(), view.reason(), view.rejectionStatus(), view.rejectionTypePreview(),
                view.rejectionTypeLength(), view.observedAt(), view.activated(), view.current(), view.resumable(), view.firstPreparedAt(),
                view.resumeDeadlineAt(), view.executionStatus(), view.refundConfirmedAt(),
                view.resume() == null ? null : RefundCancellationResumeResponse.from(view.resume()), view.operatorSubject(), view.note());
    }
}
