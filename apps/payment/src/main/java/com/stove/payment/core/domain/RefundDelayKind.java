package com.stove.payment.core.domain;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;

/** The two operator investigation lists of refund executions (decision 127). */
public enum RefundDelayKind {
    /** PENDING and unconfirmed past the original 3-hour window (R8.8). */
    DELAYED,
    /** PENDING, confirmed by a full-cancellation lookup, unattributed and not yet closed. */
    UNATTRIBUTED;

    public static RefundDelayKind parse(String value) {
        if (value == null) return DELAYED;
        for (RefundDelayKind kind : values()) if (kind.name().equals(value)) return kind;
        throw new BusinessException(ErrorCode.INVALID_REQUEST);
    }
}
