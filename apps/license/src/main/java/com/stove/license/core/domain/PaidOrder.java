package com.stove.license.core.domain;

import static com.stove.license.core.domain.LicenseValues.microsecond;
import static com.stove.license.core.domain.LicenseValues.require;
import static com.stove.license.core.domain.LicenseValues.text;
import static com.stove.license.core.domain.LicenseValues.uuid;

import java.time.Instant;

public record PaidOrder(String eventId, Instant occurredAt, String orderNo, String requestEventId,
                        String paymentResultEventId, String paymentId, String buyerSubject, Long productId,
                        String productCode, String title, long contentVersion, long amount, String currency) {
    public PaidOrder {
        validateEvent(eventId, occurredAt);
        validatePayment(orderNo, requestEventId, paymentResultEventId, paymentId);
        validateProduct(buyerSubject, productId, productCode, title, contentVersion);
        require(amount > 0 && "KRW".equals(currency), "결제 금액과 통화가 올바르지 않습니다.");
    }

    private static void validateEvent(String eventId, Instant occurredAt) {
        require(text(eventId, 64) && microsecond(occurredAt), "이벤트 식별자와 발생 시각이 올바르지 않습니다.");
    }

    private static void validatePayment(String orderNo, String requestId, String resultId, String paymentId) {
        require(uuid(orderNo) && text(requestId, 64) && text(resultId, 64) && text(paymentId, 64),
                "주문과 결제 연결 정보가 올바르지 않습니다.");
    }

    private static void validateProduct(String buyer, Long productId, String code, String title, long version) {
        require(text(buyer, 100) && productId != null && productId > 0 && LicenseValues.productCode(code)
                && text(title, 200) && version > 0, "구매자와 상품 정보가 올바르지 않습니다.");
    }
}
