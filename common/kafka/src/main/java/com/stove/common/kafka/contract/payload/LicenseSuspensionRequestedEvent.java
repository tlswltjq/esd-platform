package com.stove.common.kafka.contract.payload;

import com.stove.common.kafka.contract.DomainEvent;
import com.stove.common.kafka.contract.EventType;
import com.stove.common.kafka.contract.Topics;
import java.time.Instant;

/** Order에 환불을 위한 보유권 정지 요청이 등록됐다는 사실이며, 정지 완료를 뜻하지 않는다. */
public record LicenseSuspensionRequestedEvent(String eventId, Instant occurredAt, String refundId,
        String orderNo, String buyerSubject, Long productId, String productCode, String paymentId)
        implements DomainEvent {
    public String eventType() { return EventType.LICENSE_SUSPENSION_REQUESTED; }
    public String topic() { return Topics.ORDER; }
    public String partitionKey() { return orderNo; }
}
