package com.stove.order.core.domain;

public record RefundReconsiderationRequest(String requestId, String orderNo, String previousReviewId, String buyerSubject, String reason) {
    public RefundReconsiderationRequest {
        RefundReviewValues.requireIdentity(requestId, orderNo);
        RefundReviewValues.requireReviewId(previousReviewId);
        RefundReviewValues.requireBuyer(buyerSubject);
        RefundReviewValues.requireReason(reason);
    }
}
