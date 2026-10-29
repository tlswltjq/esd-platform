package com.stove.common.kafka.contract.payload;

import com.stove.common.kafka.contract.DomainEvent;
import com.stove.common.kafka.contract.EventType;
import com.stove.common.kafka.contract.Topics;
import java.time.Instant;

/** Order has safely closed its approval admission. This is not a supplier decline. */
public record OrderExpiredEvent(String eventId,Instant occurredAt,String orderNo,String requestEventId,
        String buyerSubject,long amount,String currency,Instant expiresAt) implements DomainEvent {
    public String eventType() { return EventType.ORDER_EXPIRED; }
    public String topic() { return Topics.ORDER; }
    public String partitionKey() { return orderNo; }
}
