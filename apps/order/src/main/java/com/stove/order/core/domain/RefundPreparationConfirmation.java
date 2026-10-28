package com.stove.order.core.domain;

import java.time.Duration;
import java.time.Instant;

public record RefundPreparationConfirmation(String eventId, Instant occurredAt, String refundId, String attemptId,
        String requestEventId, String orderNo, String paymentId, String buyerSubject, long amount, String currency,
        String licenseSuspensionEventId, String previousAttemptId, Instant firstPreparedAt, Instant confirmationDelayAt) {
    public RefundPreparationConfirmation {
        if (!RefundRestorationValues.text(eventId,64) || !RefundRestorationValues.time(occurredAt)
                || !RefundRestorationValues.uuid(refundId) || !RefundRestorationValues.uuid(attemptId)
                || !RefundRestorationValues.text(requestEventId,64) || !RefundRestorationValues.uuid(orderNo)
                || !RefundRestorationValues.text(paymentId,64) || !RefundRestorationValues.text(buyerSubject,100)
                || amount<=0 || !"KRW".equals(currency) || !RefundRestorationValues.text(licenseSuspensionEventId,64)
                || (previousAttemptId!=null && (!RefundRestorationValues.uuid(previousAttemptId) || previousAttemptId.equals(attemptId)))
                || !RefundRestorationValues.time(firstPreparedAt) || !occurredAt.equals(firstPreparedAt)
                || !RefundRestorationValues.time(confirmationDelayAt) || !confirmationDelayAt.equals(firstPreparedAt.plus(Duration.ofHours(3))))
            throw new RefundCompletionRejected("환불 준비 시각과 구매·실행 근거가 올바르지 않습니다.");
    }
    public String fingerprint() {
        return RefundRestorationValues.fingerprint(eventId,occurredAt.toString(),refundId,attemptId,requestEventId,orderNo,paymentId,
                buyerSubject,Long.toString(amount),currency,licenseSuspensionEventId,previousAttemptId==null ? "<initial>" : previousAttemptId,
                firstPreparedAt.toString(),confirmationDelayAt.toString());
    }
}
