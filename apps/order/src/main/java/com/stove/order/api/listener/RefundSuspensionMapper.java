package com.stove.order.api.listener;

import com.stove.common.contract.EventMessage;
import com.stove.common.contract.payload.LicenseSuspendedEvent;
import com.stove.order.core.domain.LicenseSuspensionConfirmation;
import com.stove.order.core.domain.RefundCompletionRejected;
import java.util.Objects;

public final class RefundSuspensionMapper {
    private RefundSuspensionMapper() {}

    public static LicenseSuspensionConfirmation from(EventMessage message, LicenseSuspendedEvent event) {
        if (message == null || event == null || !Objects.equals(message.eventId(), event.eventId())
                || !Objects.equals(message.eventType(), event.eventType()) || !Objects.equals(message.key(), event.orderNo())) {
            throw new RefundCompletionRejected("정지 완료 헤더와 본문이 다릅니다.");
        }
        return new LicenseSuspensionConfirmation(event.eventId(), event.occurredAt(), event.requestEventId(),
                event.refundId(), event.licenseId(), event.orderNo(), event.buyerSubject(), event.productId(),
                event.productCode(), event.paymentId());
    }
}
