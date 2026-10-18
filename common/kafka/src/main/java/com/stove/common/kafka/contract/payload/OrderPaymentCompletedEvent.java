package com.stove.common.kafka.contract.payload;

import com.stove.common.kafka.contract.DomainEvent;
import com.stove.common.kafka.contract.EventType;
import com.stove.common.kafka.contract.Topics;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** Final approval for one immutable OrderPlaced request. */
public record OrderPaymentCompletedEvent(String eventId, Instant occurredAt, String orderNo,
        String requestEventId, String paymentId, String buyerSubject, long amount, String currency) implements DomainEvent {
    public static OrderPaymentCompletedEvent of(String orderNo, String requestEventId, String paymentId,
                                                String buyerSubject, long amount, String currency) {
        return new OrderPaymentCompletedEvent(UUID.randomUUID().toString(), Instant.now().truncatedTo(ChronoUnit.MICROS),
                orderNo, requestEventId, paymentId, buyerSubject, amount, currency);
    }
    public String eventType() { return EventType.ORDER_PAYMENT_COMPLETED; }
    public String topic() { return Topics.PAYMENT; }
    public String partitionKey() { return orderNo; }
}
