package com.stove.common.event.payload;

import com.stove.common.event.DomainEvent;
import com.stove.common.event.EventType;
import com.stove.common.event.Topics;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 *  order → payment : 결제 대기 주문이 생성됨
 *
 * @param eventId 서비스 간 전달 시 유지하는 이벤트 고유 식별자.
 * @param occurredAt 업무 행동을 기록한 시각.
 * @param orderNo 주문·결제·이용권·정산을 연결하는 주문번호.
 * @param memberId 구매자를 식별하는 계정 ID.
 * @param totalAmount 각 주문 항목의 실결제액을 합산한 총액.
 * @param lines 구매 시점의 상품·가격·정산 조건을 담은 항목 목록.
 */
public record OrderCreatedEvent(
        String eventId,
        Instant occurredAt,
        String orderNo,
        Long memberId,
        long totalAmount,
        List<OrderLine> lines
) implements DomainEvent {

    public static OrderCreatedEvent of(String orderNo, Long memberId, long totalAmount, List<OrderLine> lines) {
        return new OrderCreatedEvent(UUID.randomUUID().toString(), Instant.now(), orderNo, memberId, totalAmount, lines);
    }

    @Override
    public String eventType() {
        return EventType.ORDER_CREATED;
    }

    @Override
    public String topic() {
        return Topics.ORDER;
    }

    @Override
    public String partitionKey() {
        return orderNo;
    }
}
