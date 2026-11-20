package com.stove.common.contract.payload;

import com.stove.common.contract.DomainEvent;
import com.stove.common.contract.EventType;
import com.stove.common.contract.Topics;
import java.time.Instant;

public record LicenseApprovalCorrectionRequestedEvent(String eventId, Instant occurredAt, String refundId, String reviewId, String stopRequestId, String licenseId, String orderNo, String buyerSubject, Long productId, String productCode, String paymentId, String suspensionRequestEventId, String suspensionEventId) implements DomainEvent {
    public String eventType() { return EventType.LICENSE_APPROVAL_CORRECTION_REQUESTED; }
    public String topic() { return Topics.ORDER; }
    public String partitionKey() { return orderNo; }
}
