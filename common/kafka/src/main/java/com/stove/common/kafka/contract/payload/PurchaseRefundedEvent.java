package com.stove.common.kafka.contract.payload;

import com.stove.common.kafka.contract.DomainEvent;
import com.stove.common.kafka.contract.EventType;
import com.stove.common.kafka.contract.Topics;
import java.time.Instant;

/** Order가 주문 한 건의 전액 환불을 완료로 확정했다는 사실. License는 해당 구매 근거를 끝낸다. */
public record PurchaseRefundedEvent(String eventId, Instant occurredAt, String refundId, String licenseId,
        String orderNo, String buyerSubject, Long productId, String productCode, String paymentId,
        String suspensionRequestEventId) implements DomainEvent {
    public String eventType() { return EventType.PURCHASE_REFUNDED; }
    public String topic() { return Topics.ORDER; }
    public String partitionKey() { return orderNo; }
}
