package com.stove.common.kafka.contract.payload;

import com.stove.common.kafka.contract.DomainEvent;
import com.stove.common.kafka.contract.EventType;
import com.stove.common.kafka.contract.Topics;
import java.time.Instant;

public record RefundExecutionFailedEvent(String eventId, Instant occurredAt, String refundId, String attemptId, String requestEventId, String orderNo, String paymentId, String buyerSubject, long amount, String currency, String providerTransactionId, String cancellationId, String lookupHash) implements DomainEvent {
    public String eventType() { return EventType.REFUND_EXECUTION_FAILED; }
    public String topic() { return Topics.PAYMENT; }
    public String partitionKey() { return orderNo; }
}
