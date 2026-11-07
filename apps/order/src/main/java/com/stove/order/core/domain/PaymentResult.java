package com.stove.order.core.domain;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import java.time.Instant;

public record PaymentResult(String eventId, Instant occurredAt, String orderNo, String requestEventId,
                            String paymentId, String buyerSubject, long amount, String currency,
                            OrderStatus status, String reasonCode, String reason, ProviderPaymentAmounts paymentAmounts) {
    public static final String REVIEW_CLOSED = "REVIEW_CLOSED";

    public PaymentResult {
        if (!text(eventId, 64) || occurredAt == null || occurredAt.getNano() % 1000 != 0
                || orderNo == null || !orderNo.matches(OrderValues.UUID_PATTERN)
                || !text(requestEventId, 64) || !text(paymentId, 64) || !text(buyerSubject, 100)
                || amount <= 0 || !"KRW".equals(currency)
                || (status != OrderStatus.PAID && status != OrderStatus.FAILED)
                || (status == OrderStatus.FAILED && (!text(reasonCode, 100) || !text(reason, 500)))
                || (status == OrderStatus.PAID && (reasonCode != null || reason != null)))
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "결제 결과가 올바르지 않습니다.");
        if (paymentAmounts != null) {
            if (status != OrderStatus.PAID) throw new BusinessException(ErrorCode.INVALID_REQUEST, "PG 금액은 승인 결과에만 제공됩니다.");
            paymentAmounts.requireTotal(amount);
        }
    }

    public PaymentResult(String eventId, Instant occurredAt, String orderNo, String requestEventId, String paymentId,
            String buyerSubject, long amount, String currency, OrderStatus status, String reasonCode, String reason) {
        this(eventId, occurredAt, orderNo, requestEventId, paymentId, buyerSubject, amount, currency, status, reasonCode, reason, null);
    }

    /** Payment가 승인 보류를 운영자 종결로 끝냈다는 실패 결과(결정 115). */
    public boolean closesHeldReview() {
        return status == OrderStatus.FAILED && REVIEW_CLOSED.equals(reasonCode);
    }

    private static boolean text(String value, int max) {
        return value != null && !value.isBlank() && value.length() <= max;
    }
}
