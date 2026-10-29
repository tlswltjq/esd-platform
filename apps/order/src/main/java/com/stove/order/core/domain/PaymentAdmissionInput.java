package com.stove.order.core.domain;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;

public record PaymentAdmissionInput(String admissionId, String orderNo, String requestEventId,
        String paymentId, String buyerSubject, long amount, String currency) {
    public PaymentAdmissionInput {
        if (admissionId == null || !admissionId.matches(OrderValues.UUID_PATTERN)
                || orderNo == null || !orderNo.matches(OrderValues.UUID_PATTERN)
                || !text(requestEventId, 64) || !text(paymentId, 64) || !text(buyerSubject, 100)
                || amount <= 0 || !"KRW".equals(currency))
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "결제 승인 진행 입력이 올바르지 않습니다.");
    }

    private static boolean text(String value, int limit) {
        return value != null && !value.isBlank() && value.length() <= limit;
    }
}
