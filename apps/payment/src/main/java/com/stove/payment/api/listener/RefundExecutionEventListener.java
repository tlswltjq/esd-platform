package com.stove.payment.api.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.event.EventHandler;
import com.stove.common.event.EventSubscriber;
import com.stove.common.event.InboundMessage;
import com.stove.common.kafka.EventEnvelope;
import com.stove.common.kafka.EventHeaders;
import com.stove.common.kafka.contract.EventType;
import com.stove.common.kafka.contract.Topics;
import com.stove.common.kafka.contract.payload.RefundExecutionRequestedEvent;
import com.stove.payment.core.service.ReceiveRefundExecution;
import com.stove.payment.core.service.RequestFirstRefundCancellation;
import com.stove.payment.core.domain.RefundExecutionRequest;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor
@EventSubscriber(topics = Topics.ORDER, group = ReceiveRefundExecution.CONSUMER_GROUP, oneAtATime = true)
public class RefundExecutionEventListener implements EventHandler {
    private final ObjectMapper mapper;
    private final ReceiveRefundExecution receive;
    private final Optional<RequestFirstRefundCancellation> cancellation;

    @Override
    public void receive(InboundMessage message) {
        EventEnvelope envelope = EventEnvelope.from(message);
        if (envelope.isType(EventType.REFUND_EXECUTION_REQUESTED)) {
            RefundExecutionRequest request = RefundExecutionMapper.from(envelope, envelope.payloadAs(mapper, RefundExecutionRequestedEvent.class));
            receive.execute(request, PaymentTraceMapper.from(message.headerValues(EventHeaders.TRACE_PARENT)));
            // The receipt transaction has committed. A duplicate receipt can still need its first dispatch.
            cancellation.ifPresent(first -> first.execute(request.attemptId()));
        }
    }
}
