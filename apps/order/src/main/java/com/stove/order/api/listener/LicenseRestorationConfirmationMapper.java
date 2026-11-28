package com.stove.order.api.listener;

import com.stove.common.contract.EventMessage;
import com.stove.common.contract.payload.LicenseRestoredEvent;
import com.stove.order.core.domain.LicenseRestorationConfirmation;
import com.stove.order.core.domain.RefundCompletionRejected;
import java.util.Objects;

public final class LicenseRestorationConfirmationMapper {
    private LicenseRestorationConfirmationMapper() { }
    public static LicenseRestorationConfirmation from(EventMessage message, LicenseRestoredEvent event) {
        if (message == null || event == null || !Objects.equals(message.eventId(), event.eventId())
                || !Objects.equals(message.eventType(), event.eventType()) || !Objects.equals(message.key(), event.orderNo()))
            throw new RefundCompletionRejected("실패/복구 헤더·본문·키가 다릅니다.");
        return new LicenseRestorationConfirmation(event.eventId(), event.occurredAt(), event.requestEventId(), event.refundId(), event.attemptId(), event.failureEventId(), event.licenseId(), event.orderNo(), event.buyerSubject(), event.productId(), event.productCode(), event.paymentId(), event.suspensionRequestEventId(), event.suspensionEventId(), event.returnedToStorage());
    }
}
