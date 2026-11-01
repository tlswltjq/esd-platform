package com.stove.settlement.api.listener;

import com.stove.common.kafka.EventEnvelope;
import com.stove.common.kafka.contract.payload.RefundPaymentConfirmedEvent;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.settlement.core.domain.ConfirmedRefund;
import java.util.Objects;

public final class SettlementRefundMapper {
    private SettlementRefundMapper() { }
    public static ConfirmedRefund from(EventEnvelope envelope, RefundPaymentConfirmedEvent event) {
        if (envelope == null || event == null || !Objects.equals(envelope.eventId(), event.eventId())
                || !Objects.equals(envelope.eventType(), event.eventType()) || !Objects.equals(envelope.key(), event.orderNo()))
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "환불 금융 확인의 봉투·본문·키가 다릅니다.");
        return new ConfirmedRefund(event.eventId(), event.occurredAt(), event.refundId(), event.attemptId(), event.requestEventId(),
                event.orderNo(), event.paymentId(), event.buyerSubject(), event.amount(), event.currency(), event.source(),
                event.providerTransactionId(), event.cancellationIds());
    }
}
