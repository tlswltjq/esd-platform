package com.stove.payment.api.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.kafka.EventEnvelope;
import com.stove.common.kafka.contract.EventType;
import com.stove.common.kafka.contract.Topics;
import com.stove.common.kafka.contract.payload.RefundExecutionRequestedEvent;
import com.stove.payment.core.service.ReceiveRefundExecution;
import com.stove.payment.core.service.RequestFirstRefundCancellation;
import com.stove.payment.core.domain.RefundExecutionRequest;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor
public class RefundExecutionEventListener {
    private final ObjectMapper mapper;
    private final ReceiveRefundExecution receive;
    private final Optional<RequestFirstRefundCancellation> cancellation;

    @KafkaListener(topics = Topics.ORDER, groupId = ReceiveRefundExecution.CONSUMER_GROUP,
            containerFactory = "paymentRefundExecutionListenerContainerFactory")
    public void receive(ConsumerRecord<String, String> record) {
        EventEnvelope envelope = EventEnvelope.from(record);
        if (envelope.isType(EventType.REFUND_EXECUTION_REQUESTED)) {
            RefundExecutionRequest request = RefundExecutionMapper.from(envelope, envelope.payloadAs(mapper, RefundExecutionRequestedEvent.class));
            receive.execute(request, PaymentTraceMapper.from(record.headers()));
            // The receipt transaction has committed. A duplicate receipt can still need its first dispatch.
            cancellation.ifPresent(first -> first.execute(request.attemptId()));
        }
    }
}
