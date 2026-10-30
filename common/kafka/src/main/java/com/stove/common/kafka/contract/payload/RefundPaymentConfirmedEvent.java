package com.stove.common.kafka.contract.payload;

import com.stove.common.kafka.contract.DomainEvent;
import com.stove.common.kafka.contract.EventType;
import com.stove.common.kafka.contract.Topics;
import java.time.Instant;
import java.util.List;

/** PG_RESPONSE also attributes success to the attempt; PG_QUERY_UNATTRIBUTED confirms only the money outcome. */
public record RefundPaymentConfirmedEvent(String eventId, Instant occurredAt, String refundId, String attemptId,
        String requestEventId, String orderNo, String paymentId, String buyerSubject, long amount, String currency,
        String source, String providerTransactionId, List<String> cancellationIds, RefundAmountsSnapshot amountDetails) implements DomainEvent {
    public RefundPaymentConfirmedEvent(String eventId, Instant occurredAt, String refundId, String attemptId,
            String requestEventId, String orderNo, String paymentId, String buyerSubject, long amount, String currency,
            String source, String providerTransactionId, List<String> cancellationIds) {
        this(eventId, occurredAt, refundId, attemptId, requestEventId, orderNo, paymentId, buyerSubject, amount, currency,
                source, providerTransactionId, cancellationIds, null);
    }
    public String eventType() { return EventType.REFUND_PAYMENT_CONFIRMED; }
    public String topic() { return Topics.PAYMENT; }
    public String partitionKey() { return orderNo; }
}
