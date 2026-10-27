package com.stove.license.api.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.kafka.EventEnvelope;
import com.stove.common.kafka.contract.EventType;
import com.stove.common.kafka.contract.Topics;
import com.stove.common.kafka.contract.payload.LicenseRestorationRequestedEvent;
import com.stove.license.core.service.ApplyLicenseRestoration;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor
public class LicenseRestorationEventListener {
    private final ObjectMapper mapper;
    private final ApplyLicenseRestoration apply;
    @KafkaListener(topics = Topics.ORDER, groupId = ApplyLicenseRestoration.CONSUMER_GROUP)
    public void receive(ConsumerRecord<String, String> record) {
        EventEnvelope envelope = EventEnvelope.from(record);
        if (envelope.isType(EventType.LICENSE_RESTORATION_REQUESTED)) apply.execute(RestorationRequestMapper.from(envelope,
                envelope.payloadAs(mapper, LicenseRestorationRequestedEvent.class)));
    }
}
