package com.stove.common.kafka.contract.payload;

import com.stove.common.kafka.contract.DomainEvent;
import com.stove.common.kafka.contract.EventType;
import com.stove.common.kafka.contract.Topics;
import java.time.Instant;

/** 복구 완료 사실. returnedToStorage가 true면 다른 쓸 수 있는 보유권이 있어 구매분이 보관함으로 돌아갔다. */
public record LicenseRestoredEvent(String eventId, Instant occurredAt, String requestEventId, String refundId, String attemptId, String failureEventId, String licenseId, String orderNo, String buyerSubject, Long productId, String productCode, String paymentId, String suspensionRequestEventId, String suspensionEventId, boolean returnedToStorage) implements DomainEvent {
    public String eventType() { return EventType.LICENSE_RESTORED; }
    public String topic() { return Topics.LICENSE; }
    public String partitionKey() { return orderNo; }
}
