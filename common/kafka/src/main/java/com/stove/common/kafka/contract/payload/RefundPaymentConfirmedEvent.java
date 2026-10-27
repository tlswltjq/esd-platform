package com.stove.common.kafka.contract.payload;

import com.stove.common.kafka.contract.DomainEvent;
import com.stove.common.kafka.contract.EventType;
import com.stove.common.kafka.contract.Topics;
import java.time.Instant;
import java.util.List;

/** PG_RESPONSE also attributes success to the attempt; PG_QUERY_UNATTRIBUTED confirms only the money outcome. */
public record RefundPaymentConfirmedEvent(String eventId, Instant occurredAt, String refundId, String attemptId,
        String requestEventId, String orderNo, String paymentId, String buyerSubject, long amount, String currency,
        String source, String providerTransactionId, List<String> cancellationIds) implements DomainEvent {
    public String eventType() { return EventType.REFUND_PAYMENT_CONFIRMED; }
    public String topic() { return Topics.PAYMENT; }
    public String partitionKey() { return orderNo; }
}
