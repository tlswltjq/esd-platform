package com.stove.common.kafka.contract.payload;

import com.stove.common.kafka.contract.DomainEvent;
import com.stove.common.kafka.contract.EventType;
import com.stove.common.kafka.contract.Topics;
import java.time.Instant;

/** Order's immutable snapshot after applying its first successful payment result. */
public record OrderPaidEvent(String eventId, Instant occurredAt, String orderNo, String requestEventId,
        String paymentResultEventId, String paymentId, String buyerSubject, Long productId,
        String productCode, String title, long contentVersion, long amount, String currency) implements DomainEvent {
    public String eventType() { return EventType.ORDER_PAID; }
    public String topic() { return Topics.ORDER; }
    public String partitionKey() { return orderNo; }
}
