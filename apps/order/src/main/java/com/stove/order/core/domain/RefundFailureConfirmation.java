package com.stove.order.core.domain;

import java.time.Instant;

/**
 * 환불 시도 실패의 내부 입력. 출처(결정 148)별로 근거가 다르다.
 * <ul>
 *   <li>{@link RefundFailureSource#PG_CANCELLATION_FAILED}: {@code cancellationId}는 FAILED로 확인된 우리 PG 취소 ID(필수),
 *       종결 ID·관측 시각은 없다.</li>
 *   <li>{@link RefundFailureSource#OPERATOR_FAILURE_CLOSED}: {@code closureId}는 Payment 실패 종결 요청 ID(필수),
 *       {@code evidenceObservedAt}은 근거 PG 재조회 시각(종결 시각 {@code occurredAt} 이전), {@code cancellationId}는 연결된
 *       실패 PG 취소가 있을 때만 그 ID다.</li>
 * </ul>
 */
public record RefundFailureConfirmation(String eventId, Instant occurredAt, String refundId, String attemptId,
        String requestEventId, String orderNo, String paymentId, String buyerSubject, long amount, String currency,
        String providerTransactionId, String cancellationId, String lookupHash, RefundFailureSource source, String closureId,
        Instant evidenceObservedAt) {
    public RefundFailureConfirmation {
        if (!RefundRestorationValues.text(eventId, 64) || !RefundRestorationValues.time(occurredAt)
                || !RefundRestorationValues.uuid(refundId) || !RefundRestorationValues.uuid(attemptId)
                || !RefundRestorationValues.text(requestEventId, 64) || !RefundRestorationValues.uuid(orderNo)
                || !RefundRestorationValues.text(paymentId, 64) || !RefundRestorationValues.text(buyerSubject, 100)
                || amount <= 0 || !"KRW".equals(currency) || !RefundRestorationValues.text(providerTransactionId, 100)
                || !RefundRestorationValues.hash(lookupHash) || !sourceEvidence(source, cancellationId, closureId, evidenceObservedAt, occurredAt))
            throw new RefundCompletionRejected("미취소 확인 실패의 근거가 올바르지 않습니다.");
    }

    /** Payment가 PG 취소 FAILED를 확인한 실패({@code RefundExecutionFailed}). */
    public static RefundFailureConfirmation pgCancellationFailed(String eventId, Instant occurredAt, String refundId, String attemptId,
            String requestEventId, String orderNo, String paymentId, String buyerSubject, long amount, String currency,
            String providerTransactionId, String cancellationId, String lookupHash) {
        return new RefundFailureConfirmation(eventId, occurredAt, refundId, attemptId, requestEventId, orderNo, paymentId, buyerSubject,
                amount, currency, providerTransactionId, cancellationId, lookupHash, RefundFailureSource.PG_CANCELLATION_FAILED, null, null);
    }

    /** 운영자 실패 종결({@code RefundFailureClosed}, 결정 128·148). */
    public static RefundFailureConfirmation operatorClosed(String eventId, Instant closedAt, String refundId, String attemptId,
            String requestEventId, String orderNo, String paymentId, String buyerSubject, long amount, String currency,
            String providerTransactionId, String failedCancellationId, String lookupHash, String closureId, Instant evidenceObservedAt) {
        return new RefundFailureConfirmation(eventId, closedAt, refundId, attemptId, requestEventId, orderNo, paymentId, buyerSubject,
                amount, currency, providerTransactionId, failedCancellationId, lookupHash, RefundFailureSource.OPERATOR_FAILURE_CLOSED,
                closureId, evidenceObservedAt);
    }

    private static boolean sourceEvidence(RefundFailureSource source, String cancellationId, String closureId, Instant observedAt, Instant occurredAt) {
        if (source == RefundFailureSource.PG_CANCELLATION_FAILED)
            return RefundRestorationValues.text(cancellationId, 100) && closureId == null && observedAt == null;
        if (source == RefundFailureSource.OPERATOR_FAILURE_CLOSED)
            return RefundRestorationValues.uuid(closureId) && (cancellationId == null || RefundRestorationValues.text(cancellationId, 100))
                    && RefundRestorationValues.time(observedAt) && !observedAt.isAfter(occurredAt);
        return false;
    }

    /**
     * 같은 실패의 재전달을 알아보는 지문. PG 확정 실패는 결정 148 이전에 저장한 지문과 같게 계산한다(결정 104). 운영자 종결은 출처와
     * 종결 근거를 함께 넣어, 같은 ID의 PG 확정 실패와 지문이 겹치지 않는다.
     */
    public String fingerprint() {
        if (source == RefundFailureSource.PG_CANCELLATION_FAILED)
            return RefundRestorationValues.fingerprint(eventId, occurredAt.toString(), refundId, attemptId, requestEventId, orderNo,
                    paymentId, buyerSubject, Long.toString(amount), currency, providerTransactionId, cancellationId, lookupHash);
        return RefundRestorationValues.fingerprint(source.name(), eventId, occurredAt.toString(), refundId, attemptId, requestEventId, orderNo,
                paymentId, buyerSubject, Long.toString(amount), currency, providerTransactionId, cancellationId == null ? "" : cancellationId,
                lookupHash, closureId, evidenceObservedAt.toString());
    }
}
