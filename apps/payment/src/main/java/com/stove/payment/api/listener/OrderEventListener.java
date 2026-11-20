package com.stove.payment.api.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.event.EventHandler;
import com.stove.common.event.EventSubscriber;
import com.stove.common.event.InboundMessage;
import com.stove.common.contract.EventEnvelope;
import com.stove.common.contract.EventHeaders;
import com.stove.common.contract.EventType;
import com.stove.common.contract.Topics;
import com.stove.common.contract.payload.OrderPlacedEvent;
import com.stove.payment.core.service.ReceiveOrderPayment;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor
@EventSubscriber(topics = Topics.ORDER, group = ReceiveOrderPayment.CONSUMER_GROUP)
public class OrderEventListener implements EventHandler {
    private final ObjectMapper mapper;
    private final ReceiveOrderPayment receiveOrderPayment;
    @Override
    public void receive(InboundMessage message) {
        EventEnvelope envelope=EventEnvelope.from(message);
        if (envelope.isType(EventType.ORDER_PLACED))
            receiveOrderPayment.execute(OrderPaymentMapper.from(envelope,envelope.payloadAs(mapper,OrderPlacedEvent.class)),
                    PaymentTraceMapper.from(message.headerValues(EventHeaders.TRACE_PARENT)));
    }
}
