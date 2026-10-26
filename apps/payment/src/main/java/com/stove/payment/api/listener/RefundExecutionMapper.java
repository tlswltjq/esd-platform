package com.stove.payment.api.listener;

import com.stove.common.kafka.EventEnvelope;
import com.stove.common.kafka.contract.payload.RefundExecutionRequestedEvent;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.payment.core.domain.RefundExecutionRequest;
import java.util.Objects;

public final class RefundExecutionMapper {
    private RefundExecutionMapper() {}

    public static RefundExecutionRequest from(EventEnvelope envelope, RefundExecutionRequestedEvent event) {
        if (envelope == null || event == null || !Objects.equals(envelope.eventId(), event.eventId())
                || !Objects.equals(envelope.eventType(), event.eventType()) || !Objects.equals(envelope.key(), event.orderNo())) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "환불 실행 요청의 봉투와 본문이 다릅니다.");
        }
        return new RefundExecutionRequest(event.eventId(), event.occurredAt(), event.refundId(), event.attemptId(),
                event.orderNo(), event.paymentId(), event.buyerSubject(), event.amount(), event.currency(), event.licenseSuspensionEventId());
    }
}
