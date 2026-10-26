package com.stove.license.core.domain;

import static com.stove.license.core.domain.LicenseValues.microsecond;
import static com.stove.license.core.domain.LicenseValues.require;
import static com.stove.license.core.domain.LicenseValues.text;
import static com.stove.license.core.domain.LicenseValues.uuid;

import java.time.Instant;

public record SuspensionRequest(String eventId, Instant occurredAt, String refundId, String orderNo,
        String buyerSubject, Long productId, String productCode, String paymentId) {
    public SuspensionRequest {
        requireEvent(eventId, occurredAt);
        requirePurchase(refundId, orderNo, buyerSubject, productId, productCode, paymentId);
    }

    private static void requireEvent(String eventId, Instant occurredAt) {
        require(text(eventId, 64) && microsecond(occurredAt), "정지 요청 이벤트 근거가 올바르지 않습니다.");
    }

    private static void requirePurchase(String refundId, String orderNo, String buyer,
            Long productId, String code, String paymentId) {
        require(uuid(refundId) && uuid(orderNo) && text(buyer, 100) && productId != null
                && productId > 0 && LicenseValues.productCode(code) && text(paymentId, 64),
                "정지 요청의 환불·구매 근거가 올바르지 않습니다.");
    }
}
