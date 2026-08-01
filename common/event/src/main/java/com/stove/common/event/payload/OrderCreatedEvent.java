package com.stove.common.event.payload;

import com.stove.common.event.DomainEvent;
import com.stove.common.event.EventType;
import com.stove.common.event.Topics;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** order → payment : 결제 대기 주문이 생성됨 */
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
