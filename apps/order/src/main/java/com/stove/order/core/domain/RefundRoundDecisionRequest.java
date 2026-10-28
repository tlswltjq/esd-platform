package com.stove.order.core.domain;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;

public record RefundRoundDecisionRequest(String reviewId, RefundReviewRequest decision) {
    public RefundRoundDecisionRequest {
        RefundReviewValues.requireReviewId(reviewId);
        if (decision == null) throw new BusinessException(ErrorCode.INVALID_REQUEST);
    }
}
