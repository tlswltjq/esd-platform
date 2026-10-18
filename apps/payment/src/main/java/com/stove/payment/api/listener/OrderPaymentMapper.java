package com.stove.payment.api.listener;

import com.stove.common.kafka.EventEnvelope;
import com.stove.common.kafka.contract.payload.OrderPlacedEvent;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.payment.core.domain.PaymentOrderRequest;
import java.util.Objects;

public final class OrderPaymentMapper {
    private OrderPaymentMapper() {}
    public static PaymentOrderRequest from(EventEnvelope envelope,OrderPlacedEvent event) {
        if (envelope==null || event==null || !Objects.equals(envelope.eventId(),event.eventId())
                || !Objects.equals(envelope.eventType(),event.eventType()) || !Objects.equals(envelope.key(),event.orderNo()))
            throw new BusinessException(ErrorCode.INVALID_REQUEST,"주문 이벤트 봉투와 본문이 다릅니다.");
        return new PaymentOrderRequest(event.eventId(),event.occurredAt(),event.orderNo(),event.buyerSubject(),event.productId(),
                event.productCode(),event.title(),event.contentVersion(),event.listAmount(),event.discountAmount(),event.amount(),event.currency());
    }
}
