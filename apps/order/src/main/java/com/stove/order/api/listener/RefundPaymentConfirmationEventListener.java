package com.stove.order.api.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.kafka.EventEnvelope;
import com.stove.common.kafka.contract.EventType;
import com.stove.common.kafka.contract.Topics;
import com.stove.common.kafka.contract.payload.RefundPaymentConfirmedEvent;
import com.stove.common.kafka.contract.payload.RefundExecutionSucceededEvent;
import com.stove.order.core.domain.RefundExecutionSuccess;
import com.stove.order.core.domain.RefundCompletionRejected;
import com.stove.order.core.domain.RefundPaymentConfirmation;
import com.stove.order.core.service.ApplyRefundPaymentConfirmation;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor
public class RefundPaymentConfirmationEventListener {
    private final ObjectMapper mapper;
    private final ApplyRefundPaymentConfirmation apply;

    @KafkaListener(topics = Topics.PAYMENT, groupId = ApplyRefundPaymentConfirmation.CONSUMER_GROUP,
            containerFactory = "orderRefundListenerContainerFactory")
    public void receive(ConsumerRecord<String, String> record) {
        RefundPaymentConfirmation input = null;
        RefundExecutionSuccess success = null;
        try {
            EventEnvelope envelope = EventEnvelope.from(record);
            if (envelope.isType(EventType.REFUND_PAYMENT_CONFIRMED))
                input = RefundPaymentConfirmationMapper.from(envelope, envelope.payloadAs(mapper, RefundPaymentConfirmedEvent.class));
            else if (envelope.isType(EventType.REFUND_EXECUTION_SUCCEEDED))
                success = RefundExecutionSuccessMapper.from(envelope, envelope.payloadAs(mapper, RefundExecutionSucceededEvent.class));
            else return;
        } catch (IllegalStateException malformed) {
            throw new RefundCompletionRejected("전액 취소 확인 메시지 계약이 올바르지 않습니다.", malformed);
        }
        if (success != null) apply.execute(success); else apply.execute(input);
    }
}
