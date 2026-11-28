package com.stove.payment.api.listener;

import com.stove.common.contract.EventMessage;
import com.stove.common.contract.payload.RefundExecutionRequestedEvent;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.payment.core.domain.RefundExecutionRequest;
import java.util.Objects;

public final class RefundExecutionMapper {
    private RefundExecutionMapper() {}

    public static RefundExecutionRequest from(EventMessage message, RefundExecutionRequestedEvent event) {
        if (message == null || event == null || !Objects.equals(message.eventId(), event.eventId())
                || !Objects.equals(message.eventType(), event.eventType()) || !Objects.equals(message.key(), event.orderNo())) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "환불 실행 요청의 헤더와 본문이 다릅니다.");
        }
        return new RefundExecutionRequest(event.eventId(), event.occurredAt(), event.refundId(), event.attemptId(),
                event.orderNo(), event.paymentId(), event.buyerSubject(), event.amount(), event.currency(), event.licenseSuspensionEventId(), event.previousAttemptId());
    }
}
