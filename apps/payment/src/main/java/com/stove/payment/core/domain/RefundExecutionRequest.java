package com.stove.payment.core.domain;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import java.time.Instant;

public record RefundExecutionRequest(String eventId, Instant occurredAt, String refundId, String attemptId,
        String orderNo, String paymentId, String buyerSubject, long amount, String currency,
        String licenseSuspensionEventId, String previousAttemptId) {
    public RefundExecutionRequest {
        requireEvent(eventId, occurredAt, licenseSuspensionEventId);
        requireIdentity(refundId, attemptId, orderNo, paymentId, buyerSubject);
        if (previousAttemptId != null && (!PaymentValues.uuid(previousAttemptId) || previousAttemptId.equals(attemptId))) throw invalid();
        if (amount <= 0 || !"KRW".equals(currency)) throw invalid();
    }

    public RefundExecutionRequest(String eventId, Instant occurredAt, String refundId, String attemptId, String orderNo, String paymentId,
            String buyerSubject, long amount, String currency, String licenseSuspensionEventId) {
        this(eventId, occurredAt, refundId, attemptId, orderNo, paymentId, buyerSubject, amount, currency, licenseSuspensionEventId, null);
    }

    private static void requireEvent(String eventId, Instant occurredAt, String suspensionEventId) {
        if (!PaymentValues.text(eventId, 64) || !PaymentValues.microsecond(occurredAt)
                || !PaymentValues.text(suspensionEventId, 64)) throw invalid();
    }

    private static void requireIdentity(String refundId, String attemptId, String orderNo, String paymentId, String buyer) {
        if (!PaymentValues.uuid(refundId) || !PaymentValues.uuid(attemptId) || !PaymentValues.uuid(orderNo)
                || !PaymentValues.text(paymentId, 64) || !PaymentValues.text(buyer, 100)) throw invalid();
    }

    private static BusinessException invalid() {
        return new BusinessException(ErrorCode.INVALID_REQUEST, "환불 실행 요청의 근거가 올바르지 않습니다.");
    }
}
