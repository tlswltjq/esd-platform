package com.stove.license.api.listener;

import com.stove.common.contract.EventMessage;
import com.stove.common.contract.payload.OrderPaidEvent;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.license.core.domain.PaidOrder;

import java.util.Objects;

public final class LicenseOrderMapper {
    private LicenseOrderMapper() {
    }

    public static PaidOrder from(EventMessage message, OrderPaidEvent event) {
        if (message == null || event == null
                || !Objects.equals(message.eventId(), event.eventId())
                || !Objects.equals(message.eventType(), event.eventType())
                || !Objects.equals(message.key(), event.orderNo()))
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "주문 이벤트 헤더와 본문이 다릅니다.");
        return new PaidOrder(event.eventId(), event.occurredAt(), event.orderNo(), event.requestEventId(),
                event.paymentResultEventId(), event.paymentId(), event.buyerSubject(), event.productId(),
                event.productCode(), event.title(), event.contentVersion(), event.amount(), event.currency());
    }
}
