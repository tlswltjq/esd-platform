package com.stove.order.api.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.event.EventHandler;
import com.stove.common.event.EventSubscriber;
import com.stove.common.event.InboundMessage;
import com.stove.common.contract.EventEnvelope;
import com.stove.common.contract.EventType;
import com.stove.common.contract.Topics;
import com.stove.common.contract.payload.RefundExecutionFailedEvent;
import com.stove.common.contract.payload.LicenseRestoredEvent;
import com.stove.order.core.domain.RefundCompletionRejected;
import com.stove.order.core.domain.RefundFailureConfirmation;
import com.stove.order.core.domain.LicenseRestorationConfirmation;
import com.stove.order.core.service.ApplyRefundRestoration;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor
@EventSubscriber(topics = {Topics.PAYMENT, Topics.LICENSE}, group = ApplyRefundRestoration.CONSUMER_GROUP, noRetryFor = RefundCompletionRejected.class)
public class RefundRestorationEventListener implements EventHandler {
    private final ObjectMapper mapper;
    private final ApplyRefundRestoration apply;
    @Override
    public void receive(InboundMessage message) {
        RefundFailureConfirmation failure = null; LicenseRestorationConfirmation restored = null;
        try {
            EventEnvelope envelope = EventEnvelope.from(message);
            if ((envelope.isType(EventType.REFUND_EXECUTION_FAILED) && !Topics.PAYMENT.equals(message.topic()))
                    || (envelope.isType(EventType.LICENSE_RESTORED) && !Topics.LICENSE.equals(message.topic())))
                throw new RefundCompletionRejected("실패/복구 이벤트의 발행 모듈 토픽이 다릅니다.");
            if (envelope.isType(EventType.REFUND_EXECUTION_FAILED))
                failure = RefundFailureConfirmationMapper.from(envelope, envelope.payloadAs(mapper, RefundExecutionFailedEvent.class));
            else if (envelope.isType(EventType.LICENSE_RESTORED))
                restored = LicenseRestorationConfirmationMapper.from(envelope, envelope.payloadAs(mapper, LicenseRestoredEvent.class));
            else return;
        } catch (IllegalStateException invalid) { throw new RefundCompletionRejected("실패/복구 이벤트 계약이 올바르지 않습니다.", invalid); }
        if (failure != null) apply.execute(failure); else apply.execute(restored);
    }
}
