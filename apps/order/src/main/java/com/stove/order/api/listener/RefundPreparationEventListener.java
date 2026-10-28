package com.stove.order.api.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.kafka.EventEnvelope;
import com.stove.common.kafka.contract.EventType;
import com.stove.common.kafka.contract.Topics;
import com.stove.common.kafka.contract.payload.RefundExecutionPreparedEvent;
import com.stove.order.core.domain.RefundPreparationConfirmation;
import com.stove.order.core.domain.RefundCompletionRejected;
import com.stove.order.core.service.ApplyRefundPreparation;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor
public class RefundPreparationEventListener {
    private final ObjectMapper mapper;
    private final ApplyRefundPreparation apply;
    @KafkaListener(topics=Topics.PAYMENT,groupId=ApplyRefundPreparation.CONSUMER_GROUP,containerFactory="orderRefundListenerContainerFactory")
    public void receive(ConsumerRecord<String,String> record) {
        RefundPreparationConfirmation input;
        try {
            EventEnvelope envelope=EventEnvelope.from(record);
            if (!envelope.isType(EventType.REFUND_EXECUTION_PREPARED)) return;
            if (!Topics.PAYMENT.equals(record.topic())) throw new RefundCompletionRejected("준비 이벤트 발행 토픽이 다릅니다.");
            input=RefundPreparationMapper.from(envelope,envelope.payloadAs(mapper,RefundExecutionPreparedEvent.class));
        } catch (IllegalStateException malformed) { throw new RefundCompletionRejected("준비 이벤트 계약이 올바르지 않습니다.",malformed); }
        apply.execute(input);
    }
}
