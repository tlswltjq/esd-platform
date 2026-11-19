package com.stove.order.api.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.event.EventHandler;
import com.stove.common.event.EventSubscriber;
import com.stove.common.event.InboundMessage;
import com.stove.common.kafka.EventEnvelope;
import com.stove.common.kafka.contract.EventType;
import com.stove.common.kafka.contract.Topics;
import com.stove.common.kafka.contract.payload.LicenseApprovalCorrectedEvent;
import com.stove.order.core.domain.ApprovalCorrectionConfirmation;
import com.stove.order.core.domain.RefundCompletionRejected;
import com.stove.order.core.service.ApplyApprovalCorrection;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor
@EventSubscriber(topics = Topics.LICENSE, group = ApplyApprovalCorrection.CONSUMER_GROUP, noRetryFor = RefundCompletionRejected.class)
public class ApprovalCorrectionEventListener implements EventHandler {
    private final ObjectMapper mapper;
    private final ApplyApprovalCorrection apply;
    @Override
    public void receive(InboundMessage message) {
        ApprovalCorrectionConfirmation input;
        try {
            EventEnvelope envelope=EventEnvelope.from(message);
            if (!envelope.isType(EventType.LICENSE_APPROVAL_CORRECTED)) return;
            if (!Topics.LICENSE.equals(message.topic())) throw new RefundCompletionRejected("복구 완료의 토픽이 다릅니다.");
            input=ApprovalCorrectionMapper.from(envelope,envelope.payloadAs(mapper,LicenseApprovalCorrectedEvent.class));
        } catch (IllegalStateException invalid) { throw new RefundCompletionRejected("승인 정정 복구 계약이 올바르지 않습니다.",invalid); }
        apply.execute(input);
    }
}
