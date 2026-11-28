package com.stove.payment.api.listener;

import com.stove.common.contract.EventMessage;
import com.stove.common.contract.payload.OrderExpiredEvent;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.payment.core.domain.OrderExpiryInput;
import java.util.Objects;

public final class OrderExpiryMapper {
    private OrderExpiryMapper() { }
    public static OrderExpiryInput from(EventMessage message,OrderExpiredEvent event) {
        if(message==null || event==null || !Objects.equals(message.eventId(),event.eventId())
                || !Objects.equals(message.eventType(),event.eventType()) || !Objects.equals(message.key(),event.orderNo()))
            throw new BusinessException(ErrorCode.INVALID_REQUEST,"Order expiry message and purchase do not match");
        return new OrderExpiryInput(event.eventId(),event.occurredAt(),event.orderNo(),event.requestEventId(),event.buyerSubject(),event.amount(),event.currency(),event.expiresAt());
    }
}
