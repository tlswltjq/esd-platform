package com.stove.payment.api.listener;

import com.stove.common.kafka.EventEnvelope;
import com.stove.common.kafka.contract.payload.OrderExpiredEvent;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.payment.core.domain.OrderExpiryInput;
import java.util.Objects;

public final class OrderExpiryMapper {
    private OrderExpiryMapper() { }
    public static OrderExpiryInput from(EventEnvelope envelope,OrderExpiredEvent event) {
        if(envelope==null || event==null || !Objects.equals(envelope.eventId(),event.eventId())
                || !Objects.equals(envelope.eventType(),event.eventType()) || !Objects.equals(envelope.key(),event.orderNo()))
            throw new BusinessException(ErrorCode.INVALID_REQUEST,"Order expiry envelope and purchase do not match");
        return new OrderExpiryInput(event.eventId(),event.occurredAt(),event.orderNo(),event.requestEventId(),event.buyerSubject(),event.amount(),event.currency(),event.expiresAt());
    }
}
