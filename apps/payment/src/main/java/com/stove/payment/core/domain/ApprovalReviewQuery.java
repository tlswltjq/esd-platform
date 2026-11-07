package com.stove.payment.core.domain;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;

public record ApprovalReviewQuery(int page, int size, String reason) {
    public ApprovalReviewQuery {
        if (page < 0 || size < 1 || size > 100 || reason != null && !ApprovalReviewReason.ALL.contains(reason))
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
    }
}
