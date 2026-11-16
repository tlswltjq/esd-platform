package com.stove.common.kafka.contract.payload;

import com.stove.common.kafka.contract.DomainEvent;
import com.stove.common.kafka.contract.EventType;
import com.stove.common.kafka.contract.Topics;
import java.time.Instant;

/**
 * 결정 148: 운영자가 PG 재조회 근거로 자동 송신이 멈춘 환불 시도를 실패로 종결했다는 사실(결정 128). PG가 취소를 실패로 확정한
 * {@link RefundExecutionFailedEvent}와 다른 사실이므로 별도 이벤트다.
 *
 * <p>{@code occurredAt}은 종결 시각, {@code closureId}는 종결 요청 ID다. {@code observedPaymentStatus}·{@code evidenceObservedAt}·
 * {@code lookupHash}는 종결 근거로 쓴 PG 재조회(원결제 상태·관측 시각·응답 원문 SHA-256)다. {@code failedCancellationId}는
 * 이 시도에 연결된 PG 취소가 있고 재조회에서 FAILED였을 때만 그 PG 취소 ID이고, 연결된 취소가 없으면 null이다.
 */
public record RefundFailureClosedEvent(String eventId, Instant occurredAt, String refundId, String attemptId, String requestEventId,
        String orderNo, String paymentId, String buyerSubject, long amount, String currency, String providerTransactionId,
        String closureId, String observedPaymentStatus, Instant evidenceObservedAt, String lookupHash, String failedCancellationId)
        implements DomainEvent {
    public String eventType() { return EventType.REFUND_FAILURE_CLOSED; }
    public String topic() { return Topics.PAYMENT; }
    public String partitionKey() { return orderNo; }
}
