package com.stove.common.event.payload;

import com.stove.common.event.DomainEvent;
import com.stove.common.event.EventType;
import com.stove.common.event.Topics;
import java.time.Instant;
import java.util.UUID;

/** order → payment/settlement : 주문이 취소됨(결제 전 취소 포함) */
public record OrderCanceledEvent(
        String eventId,
        Instant occurredAt,
        String orderNo,
        Long memberId,
        String reason
) implements DomainEvent {

    public static OrderCanceledEvent of(String orderNo, Long memberId, String reason) {
        return new OrderCanceledEvent(UUID.randomUUID().toString(), Instant.now(), orderNo, memberId, reason);
    }

    @Override
    public String eventType() {
        return EventType.ORDER_CANCELED;
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
