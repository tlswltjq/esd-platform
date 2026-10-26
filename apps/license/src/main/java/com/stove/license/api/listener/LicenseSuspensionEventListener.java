package com.stove.license.api.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.kafka.EventEnvelope;
import com.stove.common.kafka.contract.EventType;
import com.stove.common.kafka.contract.Topics;
import com.stove.common.kafka.contract.payload.LicenseSuspensionRequestedEvent;
import com.stove.license.core.service.ApplyLicenseSuspension;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor
public class LicenseSuspensionEventListener {
    private final ObjectMapper mapper;
    private final ApplyLicenseSuspension apply;

    @KafkaListener(topics = Topics.ORDER, groupId = ApplyLicenseSuspension.CONSUMER_GROUP)
    public void receive(ConsumerRecord<String, String> record) {
        EventEnvelope envelope = EventEnvelope.from(record);
        if (envelope.isType(EventType.LICENSE_SUSPENSION_REQUESTED)) {
            apply.execute(LicenseSuspensionMapper.from(envelope,
                    envelope.payloadAs(mapper, LicenseSuspensionRequestedEvent.class)));
        }
    }
}
