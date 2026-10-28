package com.stove.order.api.controller.dto;

import com.stove.order.core.domain.RefundReviewDecision;
import com.stove.order.core.domain.RefundReviewRoundView;
import java.time.Instant;

public record CustomerRefundReviewResponse(String reviewId, long reviewNo, String requestId, String previousReviewId,
        String reason, Instant receivedAt, RefundReviewDecision decision, String rejectionReason, Instant decidedAt, String origin, Instant approvalStoppedAt) {
    public static CustomerRefundReviewResponse from(RefundReviewRoundView view) {
        return view == null ? null : new CustomerRefundReviewResponse(view.reviewId(), view.reviewNo(), view.applicationRequestId(), view.previousReviewId(),
                view.customerReason(), view.receivedAt(), view.review() == null ? null : view.review().decision(),
                view.review() != null && view.review().decision() == RefundReviewDecision.REJECTED ? view.review().reason() : null,
                view.review() == null ? null : view.review().decidedAt(), view.origin(), view.approvalStop()==null ? null : view.approvalStop().receipt().receivedAt());
    }
}
