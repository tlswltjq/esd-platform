package com.stove.order.core.domain;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;

/** Explicit customer intent names the failed attempt; redelivery cannot become another retry. */
public record RefundRetryRequest(String buyerSubject, String orderNo, String previousAttemptId) {
    public RefundRetryRequest {
        if (!RefundRestorationValues.text(buyerSubject, 100) || !RefundRestorationValues.uuid(orderNo)
                || !RefundRestorationValues.uuid(previousAttemptId)) throw new BusinessException(ErrorCode.INVALID_REQUEST);
    }
}
