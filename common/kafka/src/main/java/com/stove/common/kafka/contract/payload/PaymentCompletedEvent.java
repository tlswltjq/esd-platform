package com.stove.common.kafka.contract.payload;

import com.stove.common.kafka.contract.DomainEvent;
import com.stove.common.kafka.contract.EventType;
import com.stove.common.kafka.contract.Topics;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 *  payment → license(발급) + order(확정) + settlement(집계)
 *
 * @param eventId 서비스 간 전달 시 유지하는 이벤트 고유 식별자.
 * @param occurredAt 업무 행동을 기록한 시각.
 * @param paymentId 결제 처리 기록 식별자.
 * @param orderNo 주문·결제·이용권·정산을 연결하는 주문번호.
 * @param memberId 구매자를 식별하는 계정 ID.
 * @param amount 결제 또는 환불 대상 금액(최소 화폐 단위).
 * @param method 결제 준비에 사용한 결제 수단.
 * @param lines 구매 시점의 상품·가격·정산 조건을 담은 항목 목록.
 */
public record PaymentCompletedEvent(
        String eventId,
        Instant occurredAt,
        Long paymentId,
        String orderNo,
        Long memberId,
        long amount,
        String method,
        List<OrderLine> lines
) implements DomainEvent {

    public static PaymentCompletedEvent of(Long paymentId, String orderNo, Long memberId, long amount,
                                           String method, List<OrderLine> lines) {
        return new PaymentCompletedEvent(UUID.randomUUID().toString(), Instant.now(),
                paymentId, orderNo, memberId, amount, method, lines);
    }

    @Override
    public String eventType() {
        return EventType.PAYMENT_COMPLETED;
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
