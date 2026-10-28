package com.stove.order.core.domain;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;

public record RefundReviewRequest(String requestId, String refundId, String operatorSubject,
        RefundReviewDecision decision, String reason) {
    public RefundReviewRequest {
        RefundReviewValues.requireIdentity(requestId, refundId);
        RefundReviewValues.requireOperator(operatorSubject);
        RefundReviewValues.requireReason(reason);
        if (decision == null) throw new BusinessException(ErrorCode.INVALID_REQUEST);
    }
}
