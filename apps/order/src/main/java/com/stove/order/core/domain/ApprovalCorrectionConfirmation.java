package com.stove.order.core.domain;

import java.time.Instant;

public record ApprovalCorrectionConfirmation(String eventId, Instant occurredAt, String requestEventId, String refundId,
        String reviewId, String stopRequestId, String licenseId, String orderNo, String buyerSubject, Long productId,
        String productCode, String paymentId, String suspensionRequestEventId, String suspensionEventId) {
    public ApprovalCorrectionConfirmation {
        if (!RefundRestorationValues.text(eventId,64) || !RefundRestorationValues.time(occurredAt)
                || !RefundRestorationValues.text(requestEventId,64) || !RefundRestorationValues.uuid(refundId)
                || !RefundRestorationValues.uuid(reviewId) || !RefundRestorationValues.uuid(stopRequestId)
                || !RefundRestorationValues.uuid(licenseId) || !RefundRestorationValues.uuid(orderNo)
                || !RefundRestorationValues.text(buyerSubject,100) || productId==null || productId<=0
                || productCode==null || !productCode.matches(OrderValues.PRODUCT_CODE_PATTERN)
                || !RefundRestorationValues.text(paymentId,64) || !RefundRestorationValues.text(suspensionRequestEventId,64)
                || !RefundRestorationValues.text(suspensionEventId,64)) throw new RefundCompletionRejected("승인 정정 복구 본문이 올바르지 않습니다.");
    }
    public String fingerprint() { return RefundRestorationValues.fingerprint(eventId,occurredAt.toString(),requestEventId,refundId,reviewId,
            stopRequestId,licenseId,orderNo,buyerSubject,productId.toString(),productCode,paymentId,suspensionRequestEventId,suspensionEventId); }
}
