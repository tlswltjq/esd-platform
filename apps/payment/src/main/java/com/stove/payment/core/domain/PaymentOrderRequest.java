package com.stove.payment.core.domain;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import java.time.Instant;

/** Immutable order facts received by Payment after inbound event conversion. */
public record PaymentOrderRequest(
        String eventId, Instant occurredAt, String orderNo, String buyerSubject,
        Long productId, String productCode, String title, long contentVersion,
        long listAmount, long discountAmount, long amount, String currency) {

    public PaymentOrderRequest {
        requireIdentity(eventId, orderNo, buyerSubject);
        requireProduct(productId, productCode, title, contentVersion);
        requirePrice(listAmount, discountAmount, amount, currency);
        if (!PaymentValues.microsecond(occurredAt)) throw invalid();
    }

    private static void requireIdentity(String eventId, String orderNo, String buyerSubject) {
        if (!PaymentValues.text(eventId, 64) || !PaymentValues.uuid(orderNo)
                || !PaymentValues.text(buyerSubject, 100)) throw invalid();
    }

    private static void requireProduct(Long id, String code, String title, long version) {
        if (id == null || id <= 0 || code == null || !code.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,49}")
                || !PaymentValues.text(title, 200) || version <= 0) throw invalid();
    }

    private static void requirePrice(long listAmount, long discountAmount, long amount, String currency) {
        if (listAmount <= 0 || discountAmount < 0 || discountAmount > listAmount
                || amount <= 0 || listAmount - discountAmount != amount || !"KRW".equals(currency)) throw invalid();
    }

    private static BusinessException invalid() {
        return new BusinessException(ErrorCode.INVALID_REQUEST, "주문 결제 요청이 올바르지 않습니다.");
    }
}
