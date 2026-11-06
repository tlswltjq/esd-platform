package com.stove.license.api.listener;

import com.stove.common.kafka.EventEnvelope;
import com.stove.common.kafka.contract.payload.PurchaseRefundedEvent;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.license.core.domain.PurchaseRefund;
import java.util.Objects;

public final class PurchaseRefundMapper {
    private PurchaseRefundMapper() {}

    public static PurchaseRefund from(EventEnvelope envelope, PurchaseRefundedEvent event) {
        if (envelope == null || event == null || !Objects.equals(envelope.eventId(), event.eventId())
                || !Objects.equals(envelope.eventType(), event.eventType()) || !Objects.equals(envelope.key(), event.orderNo()))
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "환불 완료 봉투와 본문이 다릅니다.");
        return new PurchaseRefund(event.eventId(), event.occurredAt(), event.refundId(), event.licenseId(), event.orderNo(),
                event.buyerSubject(), event.productId(), event.productCode(), event.paymentId(), event.suspensionRequestEventId());
    }
}
