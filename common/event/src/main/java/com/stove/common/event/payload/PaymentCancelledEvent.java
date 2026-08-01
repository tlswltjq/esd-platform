package com.stove.common.event.payload;

import com.stove.common.event.DomainEvent;
import com.stove.common.event.EventType;
import com.stove.common.event.Topics;
import java.time.Instant;
import java.util.UUID;

/** payment → license(회수) + order(취소) + settlement(역산) */
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
