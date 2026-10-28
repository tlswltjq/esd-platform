package com.stove.license.api.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.kafka.EventEnvelope;
import com.stove.common.kafka.contract.EventType;
import com.stove.common.kafka.contract.Topics;
import com.stove.common.kafka.contract.payload.LicenseApprovalCorrectionRequestedEvent;
import com.stove.license.core.service.ApplyApprovalCorrection;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor
public class ApprovalCorrectionEventListener {
    private final ObjectMapper mapper;
    private final ApplyApprovalCorrection apply;
    @KafkaListener(topics=Topics.ORDER,groupId=ApplyApprovalCorrection.CONSUMER_GROUP)
    public void receive(ConsumerRecord<String,String> record) {
        EventEnvelope envelope=EventEnvelope.from(record);
        if (envelope.isType(EventType.LICENSE_APPROVAL_CORRECTION_REQUESTED)) {
            if (!Topics.ORDER.equals(record.topic())) throw new IllegalStateException("승인 정정 요청 토픽이 다릅니다.");
            apply.execute(ApprovalCorrectionMapper.from(envelope,envelope.payloadAs(mapper,LicenseApprovalCorrectionRequestedEvent.class)));
        }
    }
}
