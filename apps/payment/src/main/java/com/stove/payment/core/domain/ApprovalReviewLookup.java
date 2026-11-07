package com.stove.payment.core.domain;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;

public record ApprovalReviewLookup(String approvalId) {
    public ApprovalReviewLookup {
        if (!PaymentValues.uuid(approvalId)) throw new BusinessException(ErrorCode.INVALID_REQUEST);
    }
}
