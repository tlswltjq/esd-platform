package com.stove.license.api.listener;

import com.stove.common.contract.EventMessage;
import com.stove.common.contract.payload.PurchaseRefundedEvent;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.license.core.domain.PurchaseRefund;
import java.util.Objects;

public final class PurchaseRefundMapper {
    private PurchaseRefundMapper() {}

    public static PurchaseRefund from(EventMessage message, PurchaseRefundedEvent event) {
        if (message == null || event == null || !Objects.equals(message.eventId(), event.eventId())
                || !Objects.equals(message.eventType(), event.eventType()) || !Objects.equals(message.key(), event.orderNo()))
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "환불 완료 헤더와 본문이 다릅니다.");
        return new PurchaseRefund(event.eventId(), event.occurredAt(), event.refundId(), event.licenseId(), event.orderNo(),
                event.buyerSubject(), event.productId(), event.productCode(), event.paymentId(), event.suspensionRequestEventId());
    }
}
