package com.stove.license.api.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.event.EventHandler;
import com.stove.common.event.EventSubscriber;
import com.stove.common.event.InboundMessage;
import com.stove.common.kafka.EventEnvelope;
import com.stove.common.kafka.contract.EventType;
import com.stove.common.kafka.contract.Topics;
import com.stove.common.kafka.contract.payload.LicenseApprovalCorrectionRequestedEvent;
import com.stove.license.core.service.ApplyApprovalCorrection;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor
@EventSubscriber(topics = Topics.ORDER, group = ApplyApprovalCorrection.CONSUMER_GROUP)
public class ApprovalCorrectionEventListener implements EventHandler {
    private final ObjectMapper mapper;
    private final ApplyApprovalCorrection apply;
    @Override
    public void receive(InboundMessage message) {
        EventEnvelope envelope=EventEnvelope.from(message);
        if (envelope.isType(EventType.LICENSE_APPROVAL_CORRECTION_REQUESTED)) {
            if (!Topics.ORDER.equals(message.topic())) throw new IllegalStateException("승인 정정 요청 토픽이 다릅니다.");
            apply.execute(ApprovalCorrectionMapper.from(envelope,envelope.payloadAs(mapper,LicenseApprovalCorrectionRequestedEvent.class)));
        }
    }
}
