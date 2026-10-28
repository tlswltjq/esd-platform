package com.stove.order.api.controller.dto;

import com.stove.order.core.domain.RefundReviewDecision;
import com.stove.order.core.domain.RefundReviewRequest;

public record RefundReviewRequestBody(String requestId, RefundReviewDecision decision, String reason) {
    public RefundReviewRequest toInput(String refundId, String operator) {
        return new RefundReviewRequest(requestId, refundId, operator, decision, reason);
    }
}
