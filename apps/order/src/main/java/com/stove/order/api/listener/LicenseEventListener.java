package com.stove.order.api.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.contract.EventMessage;
import com.stove.common.contract.EventType;
import com.stove.common.contract.Topics;
import com.stove.common.contract.payload.LicenseGrantedEvent;
import com.stove.order.core.service.ApplyLicenseGrant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;

@Component @RequiredArgsConstructor
public class LicenseEventListener {
    private final ObjectMapper mapper;
    private final ApplyLicenseGrant apply;

    @KafkaListener(topics = Topics.LICENSE, groupId = ApplyLicenseGrant.CONSUMER_GROUP)
    public void receive(ConsumerRecord<String, String> record) {
        EventMessage message = EventMessage.from(record);
        if (message.isType(EventType.LICENSE_GRANTED)) {
            apply.execute(LicenseGrantMapper.from(message, message.payloadAs(mapper, LicenseGrantedEvent.class)));
        }
    }
}
