package com.stove.license.api.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.event.EventHandler;
import com.stove.common.event.EventSubscriber;
import com.stove.common.event.InboundMessage;
import com.stove.common.kafka.EventEnvelope;
import com.stove.common.kafka.contract.EventType;
import com.stove.common.kafka.contract.Topics;
import com.stove.common.kafka.contract.payload.LicenseSuspensionRequestedEvent;
import com.stove.license.core.service.ApplyLicenseSuspension;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor
@EventSubscriber(topics = Topics.ORDER, group = ApplyLicenseSuspension.CONSUMER_GROUP)
public class LicenseSuspensionEventListener implements EventHandler {
    private final ObjectMapper mapper;
    private final ApplyLicenseSuspension apply;

    @Override
    public void receive(InboundMessage message) {
        EventEnvelope envelope = EventEnvelope.from(message);
        if (envelope.isType(EventType.LICENSE_SUSPENSION_REQUESTED)) {
            apply.execute(LicenseSuspensionMapper.from(envelope,
                    envelope.payloadAs(mapper, LicenseSuspensionRequestedEvent.class)));
        }
    }
}
