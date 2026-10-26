package com.stove.order.core.domain;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import java.time.Instant;

public record LicenseGrant(String eventId, Instant occurredAt, String licenseId, String orderNo,
        String buyerSubject, Long productId, String productCode, String paymentId) {
    public LicenseGrant {
        requireEvent(eventId, occurredAt);
        requirePurchase(licenseId, orderNo, buyerSubject, productId, productCode, paymentId);
    }

    private static void requireEvent(String eventId, Instant occurredAt) {
        if (!text(eventId, 64) || occurredAt == null || occurredAt.getNano() % 1_000 != 0) {
            throw invalid();
        }
    }

    private static void requirePurchase(String licenseId, String orderNo, String buyer,
            Long productId, String productCode, String paymentId) {
        if (!uuid(licenseId) || !uuid(orderNo) || !text(buyer, 100)
                || productId == null || productId <= 0 || productCode == null
                || !productCode.matches(OrderValues.PRODUCT_CODE_PATTERN) || !text(paymentId, 64)) {
            throw invalid();
        }
    }

    private static boolean text(String value, int limit) {
        return value != null && !value.isBlank() && value.length() <= limit;
    }

    private static boolean uuid(String value) {
        return value != null && value.matches(OrderValues.UUID_PATTERN);
    }

    private static BusinessException invalid() {
        return new BusinessException(ErrorCode.INVALID_REQUEST, "지급 확인 근거가 올바르지 않습니다.");
    }
}
