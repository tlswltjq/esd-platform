package com.stove.common.kafka.contract.payload;

import com.stove.common.kafka.contract.DomainEvent;
import com.stove.common.kafka.contract.EventType;
import com.stove.common.kafka.contract.Topics;
import java.time.Instant;

public record LicenseSuspendedEvent(String eventId, Instant occurredAt, String requestEventId,
        String refundId, String licenseId, String orderNo, String buyerSubject, Long productId,
        String productCode, String paymentId) implements DomainEvent {
    public String eventType() { return EventType.LICENSE_SUSPENDED; }
    public String topic() { return Topics.LICENSE; }
    public String partitionKey() { return orderNo; }
}
