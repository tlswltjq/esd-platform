package com.stove.order.api.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.contract.EventMessage;
import com.stove.common.contract.EventType;
import com.stove.common.contract.Topics;
import com.stove.common.contract.payload.RefundExecutionPreparedEvent;
import com.stove.order.core.domain.RefundPreparationConfirmation;
import com.stove.order.core.domain.RefundCompletionRejected;
import com.stove.order.core.service.ApplyRefundPreparation;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import com.stove.order.config.RefundKafkaConfiguration;

@Component @RequiredArgsConstructor
public class RefundPreparationEventListener {
    private final ObjectMapper mapper;
    private final ApplyRefundPreparation apply;
    @KafkaListener(topics = Topics.PAYMENT, groupId = ApplyRefundPreparation.CONSUMER_GROUP,
            containerFactory = RefundKafkaConfiguration.REFUND_COMPLETION_FACTORY)
    public void receive(ConsumerRecord<String, String> record) {
        RefundPreparationConfirmation input;
        try {
            EventMessage message=EventMessage.from(record);
            if (!message.isType(EventType.REFUND_EXECUTION_PREPARED)) return;
            if (!Topics.PAYMENT.equals(record.topic())) throw new RefundCompletionRejected("준비 이벤트 발행 토픽이 다릅니다.");
            input=RefundPreparationMapper.from(message,message.payloadAs(mapper,RefundExecutionPreparedEvent.class));
        } catch (IllegalStateException malformed) { throw new RefundCompletionRejected("준비 이벤트 계약이 올바르지 않습니다.",malformed); }
        apply.execute(input);
    }
}
