package com.stove.license.api.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.contract.EventMessage;
import com.stove.common.contract.EventType;
import com.stove.common.contract.Topics;
import com.stove.common.contract.payload.LicenseRestorationRequestedEvent;
import com.stove.license.core.service.ApplyLicenseRestoration;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;

@Component @RequiredArgsConstructor
public class LicenseRestorationEventListener {
    private final ObjectMapper mapper;
    private final ApplyLicenseRestoration apply;
    @KafkaListener(topics = Topics.ORDER, groupId = ApplyLicenseRestoration.CONSUMER_GROUP)
    public void receive(ConsumerRecord<String, String> record) {
        EventMessage message = EventMessage.from(record);
        if (message.isType(EventType.LICENSE_RESTORATION_REQUESTED)) apply.execute(RestorationRequestMapper.from(message,
                message.payloadAs(mapper, LicenseRestorationRequestedEvent.class)));
    }
}
