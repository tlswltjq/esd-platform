package com.stove.order.core.domain;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;

/** A contradictory completion requires reconciliation rather than automatic business retry. */
public class RefundCompletionRejected extends BusinessException {
    public RefundCompletionRejected(String message) {
        super(ErrorCode.CONFLICT, message);
    }

    public RefundCompletionRejected(String message, Throwable cause) {
        this(message);
        initCause(cause);
    }
}
