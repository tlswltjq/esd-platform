package com.stove.common.kafka.contract.payload;

import com.stove.common.kafka.contract.DomainEvent;
import com.stove.common.kafka.contract.EventType;
import com.stove.common.kafka.contract.Topics;
import java.time.Instant;

/** Preparation is durable evidence; it does not establish that a PG call was sent or completed. */
public record RefundExecutionPreparedEvent(String eventId, Instant occurredAt, String refundId, String attemptId,
        String requestEventId, String orderNo, String paymentId, String buyerSubject, long amount, String currency,
        String licenseSuspensionEventId, String previousAttemptId, Instant firstPreparedAt, Instant confirmationDelayAt) implements DomainEvent {
    public String eventType() { return EventType.REFUND_EXECUTION_PREPARED; }
    public String topic() { return Topics.PAYMENT; }
    public String partitionKey() { return orderNo; }
}
