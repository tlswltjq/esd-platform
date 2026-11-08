package com.stove.payment.core.domain;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;

/** The operator investigation lists of refund executions (decisions 127·128). */
public enum RefundDelayKind {
    /** PENDING and unconfirmed past the original 3-hour window (R8.8). */
    DELAYED,
    /** PENDING, confirmed by a full-cancellation lookup, unattributed and not yet closed. */
    UNATTRIBUTED,
    /** FAILED, and a later lookup found a full cancellation of the original payment (decision 128). */
    LATE_CANCELLATION;

    public static RefundDelayKind parse(String value) {
        if (value == null) return DELAYED;
        for (RefundDelayKind kind : values()) if (kind.name().equals(value)) return kind;
        throw new BusinessException(ErrorCode.INVALID_REQUEST);
    }
}
