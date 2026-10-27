package com.stove.common.kafka.contract.payload;

import com.stove.common.kafka.contract.DomainEvent;
import com.stove.common.kafka.contract.EventType;
import com.stove.common.kafka.contract.Topics;
import java.time.Instant;

public record LicenseRestoredEvent(String eventId, Instant occurredAt, String requestEventId, String refundId, String attemptId, String failureEventId, String licenseId, String orderNo, String buyerSubject, Long productId, String productCode, String paymentId, String suspensionRequestEventId, String suspensionEventId) implements DomainEvent {
    public String eventType() { return EventType.LICENSE_RESTORED; }
    public String topic() { return Topics.LICENSE; }
    public String partitionKey() { return orderNo; }
}
