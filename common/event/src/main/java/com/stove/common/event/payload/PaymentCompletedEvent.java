package com.stove.common.event.payload;

import com.stove.common.event.DomainEvent;
import com.stove.common.event.EventType;
import com.stove.common.event.Topics;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** payment → license(발급) + order(확정) + settlement(집계) */
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
