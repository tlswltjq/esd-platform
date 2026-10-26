package com.stove.order.core.domain;

import java.time.Instant;

public record LicenseSuspensionConfirmation(String eventId, Instant occurredAt, String requestEventId,
        String refundId, String licenseId, String orderNo, String buyerSubject, Long productId,
        String productCode, String paymentId) {
    public LicenseSuspensionConfirmation {
        requireEvent(eventId, occurredAt, requestEventId);
        requirePurchase(refundId, licenseId, orderNo, buyerSubject, productId, productCode, paymentId);
    }

    private static void requireEvent(String eventId, Instant occurredAt, String requestEventId) {
        if (!text(eventId, 64) || !text(requestEventId, 64) || occurredAt == null || occurredAt.getNano() % 1_000 != 0) {
            throw new RefundCompletionRejected("정지 완료 이벤트의 근거가 올바르지 않습니다.");
        }
    }

    private static void requirePurchase(String refundId, String licenseId, String orderNo, String buyer,
            Long productId, String code, String paymentId) {
        if (!uuid(refundId) || !uuid(licenseId) || !uuid(orderNo) || !text(buyer, 100)
                || productId == null || productId <= 0 || code == null
                || !code.matches(OrderValues.PRODUCT_CODE_PATTERN) || !text(paymentId, 64)) {
            throw new RefundCompletionRejected("정지 완료의 환불·구매 근거가 올바르지 않습니다.");
        }
    }

    private static boolean uuid(String value) {
        return value != null && value.matches(OrderValues.UUID_PATTERN);
    }

    private static boolean text(String value, int max) {
        return value != null && !value.isBlank() && value.length() <= max;
    }
}
