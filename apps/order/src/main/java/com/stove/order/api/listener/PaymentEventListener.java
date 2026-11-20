package com.stove.order.api.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.event.EventHandler;
import com.stove.common.event.EventSubscriber;
import com.stove.common.event.InboundMessage;
import com.stove.common.contract.EventEnvelope;
import com.stove.common.contract.EventType;
import com.stove.common.contract.Topics;
import com.stove.common.contract.payload.OrderPaymentCompletedEvent;
import com.stove.common.contract.payload.OrderPaymentFailedEvent;
import com.stove.order.core.service.ApplyPaymentResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor
@EventSubscriber(topics = Topics.PAYMENT, group = ApplyPaymentResult.CONSUMER_GROUP)
public class PaymentEventListener implements EventHandler {
    private final ObjectMapper mapper;
    private final ApplyPaymentResult applyPaymentResult;
    @Override
    public void receive(InboundMessage message) {
        EventEnvelope envelope = EventEnvelope.from(message);
        if (envelope.isType(EventType.ORDER_PAYMENT_COMPLETED))
            applyPaymentResult.execute(envelope.eventType(), PaymentResultMapper.from(
                    envelope, envelope.payloadAs(mapper, OrderPaymentCompletedEvent.class)));
        else if (envelope.isType(EventType.ORDER_PAYMENT_FAILED))
            applyPaymentResult.execute(envelope.eventType(), PaymentResultMapper.from(
                    envelope, envelope.payloadAs(mapper, OrderPaymentFailedEvent.class)));
    }
}
