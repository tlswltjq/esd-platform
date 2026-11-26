package com.stove.license.api.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.contract.EventEnvelope;
import com.stove.common.contract.EventType;
import com.stove.common.contract.Topics;
import com.stove.common.contract.payload.LicenseSuspensionRequestedEvent;
import com.stove.license.core.service.ApplyLicenseSuspension;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;

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
