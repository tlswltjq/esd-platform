package com.stove.payment.api.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.event.EventHandler;
import com.stove.common.event.EventSubscriber;
import com.stove.common.event.InboundMessage;
import com.stove.common.contract.EventEnvelope;
import com.stove.common.contract.EventType;
import com.stove.common.contract.Topics;
import com.stove.common.contract.payload.OrderExpiredEvent;
import com.stove.payment.core.service.ApplyOrderExpiry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@EventSubscriber(topics = Topics.ORDER, group = ApplyOrderExpiry.CONSUMER_GROUP)
public class OrderExpiryListener implements EventHandler {
    private final ObjectMapper mapper;
    private final ApplyOrderExpiry apply;
    @Override
    public void receive(InboundMessage message) {
        EventEnvelope envelope=EventEnvelope.from(message);
        if(envelope.isType(EventType.ORDER_EXPIRED))apply.execute(OrderExpiryMapper.from(envelope,envelope.payloadAs(mapper,OrderExpiredEvent.class)));
    }
}
