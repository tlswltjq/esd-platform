package com.stove.common.kafka.contract.payload;

import com.stove.common.kafka.contract.DomainEvent;
import com.stove.common.kafka.contract.EventType;
import com.stove.common.kafka.contract.Topics;
import java.time.Instant;

/** Order has verified suspension and durably registered one full-refund execution. */
public record RefundExecutionRequestedEvent(String eventId, Instant occurredAt, String refundId,
        String attemptId, String orderNo, String paymentId, String buyerSubject, long amount,
        String currency, String licenseSuspensionEventId, String previousAttemptId) implements DomainEvent {
    public RefundExecutionRequestedEvent(String eventId, Instant occurredAt, String refundId, String attemptId, String orderNo,
            String paymentId, String buyerSubject, long amount, String currency, String licenseSuspensionEventId) {
        this(eventId, occurredAt, refundId, attemptId, orderNo, paymentId, buyerSubject, amount, currency, licenseSuspensionEventId, null);
    }
    public String eventType() { return EventType.REFUND_EXECUTION_REQUESTED; }
    public String topic() { return Topics.ORDER; }
    public String partitionKey() { return orderNo; }
}
