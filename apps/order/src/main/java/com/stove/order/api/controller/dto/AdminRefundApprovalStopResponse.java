package com.stove.order.api.controller.dto;

import com.stove.order.core.domain.RefundApprovalStopView;
import java.time.Instant;

public record AdminRefundApprovalStopResponse(RefundApprovalStopResponse receipt, Instant restorationRequestedAt, Instant restoredAt,
        Instant reopenedAt, String nextReviewId) {
    public static AdminRefundApprovalStopResponse from(RefundApprovalStopView view) {
        return view==null ? null : new AdminRefundApprovalStopResponse(RefundApprovalStopResponse.from(view.receipt()),view.restorationRequestedAt(),view.restoredAt(),view.reopenedAt(),view.nextReviewId());
    }
}
