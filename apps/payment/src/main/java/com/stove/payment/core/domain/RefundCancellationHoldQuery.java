package com.stove.payment.core.domain;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;

public record RefundCancellationHoldQuery(boolean activeOnly, int page, int size) {
    public RefundCancellationHoldQuery {
        if (page < 0 || size < 1 || size > 100) throw new BusinessException(ErrorCode.INVALID_REQUEST);
    }
}
