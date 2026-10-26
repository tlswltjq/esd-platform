package com.stove.order.api.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.kafka.EventEnvelope;
import com.stove.common.kafka.contract.EventType;
import com.stove.common.kafka.contract.Topics;
import com.stove.common.kafka.contract.payload.LicenseSuspendedEvent;
import com.stove.order.core.domain.LicenseSuspensionConfirmation;
import com.stove.order.core.domain.RefundCompletionRejected;
import com.stove.order.core.service.ApplyRefundSuspension;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor
public class RefundSuspensionEventListener {
    private final ObjectMapper mapper;
    private final ApplyRefundSuspension apply;

    @KafkaListener(topics = Topics.LICENSE, groupId = ApplyRefundSuspension.CONSUMER_GROUP,
            containerFactory = "orderRefundListenerContainerFactory")
    public void receive(ConsumerRecord<String, String> record) {
        LicenseSuspensionConfirmation input;
        try {
            EventEnvelope envelope = EventEnvelope.from(record);
            if (!envelope.isType(EventType.LICENSE_SUSPENDED)) return;
            input = RefundSuspensionMapper.from(envelope, envelope.payloadAs(mapper, LicenseSuspendedEvent.class));
        } catch (IllegalStateException malformed) {
            throw new RefundCompletionRejected("정지 완료 메시지 계약이 올바르지 않습니다.", malformed);
        }
        apply.execute(input);
    }
}
