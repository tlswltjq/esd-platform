package com.stove.payment.api.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.contract.EventMessage;
import com.stove.common.contract.EventHeaders;
import com.stove.common.contract.EventType;
import com.stove.common.contract.Topics;
import com.stove.common.contract.payload.RefundExecutionRequestedEvent;
import com.stove.payment.core.service.ReceiveRefundExecution;
import com.stove.payment.core.service.RequestFirstRefundCancellation;
import com.stove.payment.core.domain.RefundExecutionRequest;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import com.stove.payment.config.RefundKafkaConfiguration;

@Component @RequiredArgsConstructor
public class RefundExecutionEventListener {
    private final ObjectMapper mapper;
    private final ReceiveRefundExecution receive;
    private final Optional<RequestFirstRefundCancellation> cancellation;

    @KafkaListener(topics = Topics.ORDER, groupId = ReceiveRefundExecution.CONSUMER_GROUP,
            containerFactory = RefundKafkaConfiguration.REFUND_EXECUTION_FACTORY)
    public void receive(ConsumerRecord<String, String> record) {
        EventMessage message = EventMessage.from(record);
        if (message.isType(EventType.REFUND_EXECUTION_REQUESTED)) {
            RefundExecutionRequest request = RefundExecutionMapper.from(message, message.payloadAs(mapper, RefundExecutionRequestedEvent.class));
            receive.execute(request, PaymentTraceMapper.from(EventHeaders.values(record.headers(), EventHeaders.TRACE_PARENT)));
            // The receipt transaction has committed. A duplicate receipt can still need its first dispatch.
            cancellation.ifPresent(first -> first.execute(request.attemptId()));
        }
    }
}
