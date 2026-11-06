package com.stove.license.core.domain;

import static com.stove.license.core.domain.LicenseValues.microsecond;
import static com.stove.license.core.domain.LicenseValues.require;
import static com.stove.license.core.domain.LicenseValues.text;
import static com.stove.license.core.domain.LicenseValues.uuid;

import java.time.Instant;

/** Order의 환불 완료 사실을 License 내부 입력으로 바꾼 값. */
public record PurchaseRefund(String eventId, Instant occurredAt, String refundId, String licenseId, String orderNo,
        String buyerSubject, Long productId, String productCode, String paymentId, String suspensionRequestEventId) {
    public PurchaseRefund {
        require(text(eventId, 64) && microsecond(occurredAt), "환불 완료 이벤트 근거가 올바르지 않습니다.");
        require(uuid(refundId) && uuid(licenseId) && uuid(orderNo) && text(buyerSubject, 100) && productId != null && productId > 0
                && LicenseValues.productCode(productCode) && text(paymentId, 64) && text(suspensionRequestEventId, 64),
                "환불 완료의 구매 근거가 올바르지 않습니다.");
    }
}
