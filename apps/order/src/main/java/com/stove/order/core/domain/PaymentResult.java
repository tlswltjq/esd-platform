package com.stove.order.core.domain;

import java.time.Instant;
import com.stove.common.web.error.*;

public record PaymentResult(String eventId, Instant occurredAt, String orderNo, String requestEventId,
                            String paymentId, String buyerSubject, long amount, String currency,
                            OrderStatus status, String reasonCode, String reason) {
    public PaymentResult {
        if (!text(eventId, 64) || occurredAt == null || occurredAt.getNano() % 1000 != 0
                || orderNo == null || !orderNo.matches(OrderValues.UUID_PATTERN)
                || !text(requestEventId, 64) || !text(paymentId, 64) || !text(buyerSubject, 100)
                || amount <= 0 || !"KRW".equals(currency)
                || (status != OrderStatus.PAID && status != OrderStatus.FAILED)
                || (status == OrderStatus.FAILED && (!text(reasonCode, 100) || !text(reason, 500)))
                || (status == OrderStatus.PAID && (reasonCode != null || reason != null)))
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "결제 결과가 올바르지 않습니다.");
    }

    private static boolean text(String value, int max) {
        return value != null && !value.isBlank() && value.length() <= max;
    }
}
