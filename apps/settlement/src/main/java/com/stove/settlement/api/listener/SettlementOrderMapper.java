package com.stove.settlement.api.listener;

import com.stove.common.kafka.EventEnvelope;
import com.stove.common.kafka.contract.payload.OrderPaidEvent;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.settlement.core.domain.PaidOrder;

import java.util.Objects;

public final class SettlementOrderMapper {
    private SettlementOrderMapper() {
    }

    public static PaidOrder from(EventEnvelope envelope, OrderPaidEvent event) {
        if (envelope == null || event == null
                || !Objects.equals(envelope.eventId(), event.eventId())
                || !Objects.equals(envelope.eventType(), event.eventType())
                || !Objects.equals(envelope.key(), event.orderNo()))
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "주문 이벤트 봉투와 본문이 다릅니다.");
        return new PaidOrder(event.eventId(), event.occurredAt(), event.orderNo(), event.requestEventId(),
                event.paymentResultEventId(), event.paymentId(), event.buyerSubject(), event.productId(),
                event.productCode(), event.title(), event.contentVersion(), event.amount(), event.currency());
    }
}
