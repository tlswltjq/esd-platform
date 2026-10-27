package com.stove.order.core.domain;

import java.time.Instant;

public record RefundFailureConfirmation(String eventId, Instant occurredAt, String refundId, String attemptId,
        String requestEventId, String orderNo, String paymentId, String buyerSubject, long amount, String currency,
        String providerTransactionId, String cancellationId, String lookupHash) {
    public RefundFailureConfirmation {
        if (!RefundRestorationValues.text(eventId, 64) || !RefundRestorationValues.time(occurredAt)
                || !RefundRestorationValues.uuid(refundId) || !RefundRestorationValues.uuid(attemptId)
                || !RefundRestorationValues.text(requestEventId, 64) || !RefundRestorationValues.uuid(orderNo)
                || !RefundRestorationValues.text(paymentId, 64) || !RefundRestorationValues.text(buyerSubject, 100)
                || amount <= 0 || !"KRW".equals(currency) || !RefundRestorationValues.text(providerTransactionId, 100)
                || !RefundRestorationValues.text(cancellationId, 100) || !RefundRestorationValues.hash(lookupHash))
            throw new RefundCompletionRejected("미취소 확인 실패의 근거가 올바르지 않습니다.");
    }
    public String fingerprint() {
        return RefundRestorationValues.fingerprint(eventId, occurredAt.toString(), refundId, attemptId, requestEventId, orderNo,
                paymentId, buyerSubject, Long.toString(amount), currency, providerTransactionId, cancellationId, lookupHash);
    }
}
