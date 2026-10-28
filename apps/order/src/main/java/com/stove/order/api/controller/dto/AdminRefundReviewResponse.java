package com.stove.order.api.controller.dto;

import com.stove.order.core.domain.RefundReviewRoundView;
import java.time.Instant;

public record AdminRefundReviewResponse(String reviewId, String refundId, long reviewNo, String requestId, String previousReviewId,
        String customerReason, Instant receivedAt, AdminRefundDecisionResponse review, String origin, String correctionRequestId, AdminRefundApprovalStopResponse approvalStop) {
    public static AdminRefundReviewResponse fromDecision(RefundReviewRoundView view) {
        return new AdminRefundReviewResponse(view.reviewId(),view.refundId(),view.reviewNo(),view.applicationRequestId(),view.previousReviewId(),
                view.customerReason(),view.receivedAt(),AdminRefundDecisionResponse.from(view.review()),view.origin(),view.correctionRequestId(),null);
    }
    public static AdminRefundReviewResponse from(RefundReviewRoundView view) {
        return view == null ? null : new AdminRefundReviewResponse(view.reviewId(), view.refundId(), view.reviewNo(), view.applicationRequestId(),
                view.previousReviewId(), view.customerReason(), view.receivedAt(), AdminRefundDecisionResponse.from(view.review()), view.origin(), view.correctionRequestId(), AdminRefundApprovalStopResponse.from(view.approvalStop()));
    }
}
