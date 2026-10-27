package com.stove.common.kafka.contract.payload;

import com.stove.common.kafka.contract.DomainEvent;
import com.stove.common.kafka.contract.EventType;
import com.stove.common.kafka.contract.Topics;
import java.time.Instant;

/** A late direct response enriches the attempt while preserving its first financial confirmation. */
public record RefundExecutionSucceededEvent(String eventId, Instant occurredAt, String confirmationEventId,
        String refundId, String attemptId, String requestEventId, String orderNo, String paymentId, String buyerSubject,
        long amount, String currency, String providerTransactionId, String cancellationId) implements DomainEvent {
    public String eventType() { return EventType.REFUND_EXECUTION_SUCCEEDED; }
    public String topic() { return Topics.PAYMENT; }
    public String partitionKey() { return orderNo; }
}
