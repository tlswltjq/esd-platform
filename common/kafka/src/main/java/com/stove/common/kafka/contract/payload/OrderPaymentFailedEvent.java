package com.stove.common.kafka.contract.payload;

import com.stove.common.kafka.contract.DomainEvent;
import com.stove.common.kafka.contract.EventType;
import com.stove.common.kafka.contract.Topics;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** Final failure, after Payment has resolved its individual payment attempts. */
public record OrderPaymentFailedEvent(String eventId, Instant occurredAt, String orderNo,
        String requestEventId, String paymentId, String buyerSubject, long amount, String currency,
        String reasonCode, String reason) implements DomainEvent {
    public static OrderPaymentFailedEvent of(String orderNo, String requestEventId, String paymentId,
                    String buyerSubject, long amount, String currency, String reasonCode, String reason) {
        return new OrderPaymentFailedEvent(UUID.randomUUID().toString(), Instant.now().truncatedTo(ChronoUnit.MICROS),
                orderNo, requestEventId, paymentId, buyerSubject, amount, currency, reasonCode, reason);
    }
    public String eventType() { return EventType.ORDER_PAYMENT_FAILED; }
    public String topic() { return Topics.PAYMENT; }
    public String partitionKey() { return orderNo; }
}
