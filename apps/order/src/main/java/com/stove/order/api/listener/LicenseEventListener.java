package com.stove.order.api.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.event.EventHandler;
import com.stove.common.event.EventSubscriber;
import com.stove.common.event.InboundMessage;
import com.stove.common.kafka.EventEnvelope;
import com.stove.common.kafka.contract.EventType;
import com.stove.common.kafka.contract.Topics;
import com.stove.common.kafka.contract.payload.LicenseGrantedEvent;
import com.stove.order.core.service.ApplyLicenseGrant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor
@EventSubscriber(topics = Topics.LICENSE, group = ApplyLicenseGrant.CONSUMER_GROUP)
public class LicenseEventListener implements EventHandler {
    private final ObjectMapper mapper;
    private final ApplyLicenseGrant apply;

    @Override
    public void receive(InboundMessage message) {
        EventEnvelope envelope = EventEnvelope.from(message);
        if (envelope.isType(EventType.LICENSE_GRANTED)) {
            apply.execute(LicenseGrantMapper.from(envelope, envelope.payloadAs(mapper, LicenseGrantedEvent.class)));
        }
    }
}
