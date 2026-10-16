package com.stove.common.kafka.contract.payload;

import com.stove.common.kafka.contract.*;
import java.time.Instant;

/** Immutable order terms. amount is the customer charge, before any provider fees. */
public record OrderPlacedEvent(String eventId, Instant occurredAt, String orderNo, String buyerSubject,
                               Long productId, String productCode, String title, long contentVersion,
                               long listAmount, long discountAmount, long amount, String currency) implements DomainEvent {
    public String eventType() { return EventType.ORDER_PLACED; }
    public String topic() { return Topics.ORDER; }
    public String partitionKey() { return orderNo; }
}
