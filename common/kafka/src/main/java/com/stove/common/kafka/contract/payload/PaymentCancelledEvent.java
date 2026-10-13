package com.stove.common.kafka.contract.payload;

import com.stove.common.kafka.contract.DomainEvent;
import com.stove.common.kafka.contract.EventType;
import com.stove.common.kafka.contract.Topics;
import java.time.Instant;
import java.util.UUID;

/**
 *  payment → license(회수) + order(취소) + settlement(역산)
 *
 * @param eventId 서비스 간 전달 시 유지하는 이벤트 고유 식별자.
 * @param occurredAt 업무 행동을 기록한 시각.
 * @param paymentId 결제 처리 기록 식별자.
 * @param orderNo 주문·결제·이용권·정산을 연결하는 주문번호.
 * @param memberId 구매자를 식별하는 계정 ID.
 * @param refundAmount 환불 처리한 금액(최소 화폐 단위).
 * @param reason 처리 결과 또는 요청 사유에 대한 설명.
 */
public record PaymentCancelledEvent(
        String eventId,
        Instant occurredAt,
        Long paymentId,
        String orderNo,
        Long memberId,
        long refundAmount,
        String reason
) implements DomainEvent {

    public static PaymentCancelledEvent of(Long paymentId, String orderNo, Long memberId,
                                           long refundAmount, String reason) {
        return new PaymentCancelledEvent(UUID.randomUUID().toString(), Instant.now(),
                paymentId, orderNo, memberId, refundAmount, reason);
    }

    @Override
    public String eventType() {
        return EventType.PAYMENT_CANCELLED;
    }

    @Override
    public String topic() {
        return Topics.PAYMENT;
    }

    @Override
    public String partitionKey() {
        return orderNo;
    }
}
