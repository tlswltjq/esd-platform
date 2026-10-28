package com.stove.payment.core.domain;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;

public record RefundCancellationHoldLookup(String holdId) {
    public RefundCancellationHoldLookup {
        if (!PaymentValues.uuid(holdId)) throw new BusinessException(ErrorCode.INVALID_REQUEST);
    }
}
