package com.stove.order.core.domain;

import java.time.Instant;

public record LicenseRestorationConfirmation(String eventId, Instant occurredAt, String requestEventId, String refundId, String attemptId, String failureEventId, String licenseId, String orderNo, String buyerSubject, Long productId, String productCode, String paymentId, String suspensionRequestEventId, String suspensionEventId) {
    public LicenseRestorationConfirmation {
        if (!RefundRestorationValues.text(eventId, 64) || !RefundRestorationValues.time(occurredAt) || !RefundRestorationValues.text(requestEventId, 64)
                || !RefundRestorationValues.uuid(refundId) || !RefundRestorationValues.uuid(attemptId) || !RefundRestorationValues.uuid(licenseId)
                || !RefundRestorationValues.text(failureEventId, 64) || !RefundRestorationValues.uuid(orderNo)
                || !RefundRestorationValues.text(buyerSubject, 100) || productId == null || productId <= 0
                || productCode == null || !productCode.matches(OrderValues.PRODUCT_CODE_PATTERN) || !RefundRestorationValues.text(paymentId, 64)
                || !RefundRestorationValues.text(suspensionRequestEventId, 64) || !RefundRestorationValues.text(suspensionEventId, 64))
            throw new RefundCompletionRejected("복구 완료의 근거가 올바르지 않습니다.");
    }
    public String fingerprint() {
        return RefundRestorationValues.fingerprint(eventId, occurredAt.toString(), requestEventId, refundId, attemptId, failureEventId,
                licenseId, orderNo, buyerSubject, productId.toString(), productCode, paymentId, suspensionRequestEventId, suspensionEventId);
    }
}
