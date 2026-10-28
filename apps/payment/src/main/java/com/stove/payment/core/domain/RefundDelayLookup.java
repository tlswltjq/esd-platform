package com.stove.payment.core.domain;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;

public record RefundDelayLookup(String attemptId) {
    public RefundDelayLookup {
        if (!PaymentValues.uuid(attemptId)) throw new BusinessException(ErrorCode.INVALID_REQUEST);
    }
}
