package com.stove.order.core.domain;

public record RefundApprovalStopRequest(String requestId, String refundId, String reviewId, String operatorSubject, String reason) {
    public RefundApprovalStopRequest {
        RefundReviewValues.requireIdentity(requestId, refundId);
        RefundReviewValues.requireReviewId(reviewId);
        RefundReviewValues.requireOperator(operatorSubject);
        RefundReviewValues.requireReason(reason);
    }
}
