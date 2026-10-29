package com.stove.payment.core.domain;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;

public record PaymentApprovalInput(String requestId, String orderNo, String supplierAttemptId, ApprovalToken token) {
    public PaymentApprovalInput {
        requireCommandIdentity(requestId, orderNo);
        requireAuthenticationResult(supplierAttemptId, token);
    }
    private static void requireCommandIdentity(String requestId, String orderNo) {
        if (!PaymentValues.uuid(requestId) || !PaymentValues.uuid(orderNo))
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "Invalid approval identity");
    }
    private static void requireAuthenticationResult(String attemptId, ApprovalToken token) {
        if (!PaymentValues.text(attemptId, 100) || token == null)
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "An authenticated payment attempt is required");
    }
}
