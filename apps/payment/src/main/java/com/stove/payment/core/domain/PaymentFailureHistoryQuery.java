package com.stove.payment.core.domain;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;

public record PaymentFailureHistoryQuery(String orderNo, String buyerSubject, int page, int size) {
    public PaymentFailureHistoryQuery {
        if (!PaymentValues.uuid(orderNo) || !PaymentValues.text(buyerSubject, 100) || page < 0 || size < 1 || size > 100)
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
    }
}
