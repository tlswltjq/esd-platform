package com.stove.license.api.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.event.EventHandler;
import com.stove.common.event.EventSubscriber;
import com.stove.common.event.InboundMessage;
import com.stove.common.contract.EventEnvelope;
import com.stove.common.contract.EventType;
import com.stove.common.contract.Topics;
import com.stove.common.contract.payload.LicenseRestorationRequestedEvent;
import com.stove.license.core.service.ApplyLicenseRestoration;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor
@EventSubscriber(topics = Topics.ORDER, group = ApplyLicenseRestoration.CONSUMER_GROUP)
public class LicenseRestorationEventListener implements EventHandler {
    private final ObjectMapper mapper;
    private final ApplyLicenseRestoration apply;
    @Override
    public void receive(InboundMessage message) {
        EventEnvelope envelope = EventEnvelope.from(message);
        if (envelope.isType(EventType.LICENSE_RESTORATION_REQUESTED)) apply.execute(RestorationRequestMapper.from(envelope,
                envelope.payloadAs(mapper, LicenseRestorationRequestedEvent.class)));
    }
}
