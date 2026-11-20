package com.stove.common.contract.payload;

import com.stove.common.contract.DomainEvent;
import com.stove.common.contract.EventType;
import com.stove.common.contract.Topics;
import java.time.Instant;

/** A late direct response enriches the attempt while preserving its first financial confirmation. */
public record RefundExecutionSucceededEvent(String eventId, Instant occurredAt, String confirmationEventId,
        String refundId, String attemptId, String requestEventId, String orderNo, String paymentId, String buyerSubject,
        long amount, String currency, String providerTransactionId, String cancellationId, RefundAmountsSnapshot amountDetails) implements DomainEvent {
    public RefundExecutionSucceededEvent(String eventId, Instant occurredAt, String confirmationEventId,
            String refundId, String attemptId, String requestEventId, String orderNo, String paymentId, String buyerSubject,
            long amount, String currency, String providerTransactionId, String cancellationId) {
        this(eventId, occurredAt, confirmationEventId, refundId, attemptId, requestEventId, orderNo, paymentId, buyerSubject,
                amount, currency, providerTransactionId, cancellationId, null);
    }
    public String eventType() { return EventType.REFUND_EXECUTION_SUCCEEDED; }
    public String topic() { return Topics.PAYMENT; }
    public String partitionKey() { return orderNo; }
}
