package com.stove.order.api.listener;

import com.stove.common.kafka.EventEnvelope;
import com.stove.common.kafka.contract.payload.LicenseRestoredEvent;
import com.stove.order.core.domain.LicenseRestorationConfirmation;
import com.stove.order.core.domain.RefundCompletionRejected;
import java.util.Objects;

public final class LicenseRestorationConfirmationMapper {
    private LicenseRestorationConfirmationMapper() { }
    public static LicenseRestorationConfirmation from(EventEnvelope envelope, LicenseRestoredEvent event) {
        if (envelope == null || event == null || !Objects.equals(envelope.eventId(), event.eventId())
                || !Objects.equals(envelope.eventType(), event.eventType()) || !Objects.equals(envelope.key(), event.orderNo()))
            throw new RefundCompletionRejected("실패/복구 봉투·본문·키가 다릅니다.");
        return new LicenseRestorationConfirmation(event.eventId(), event.occurredAt(), event.requestEventId(), event.refundId(), event.attemptId(), event.failureEventId(), event.licenseId(), event.orderNo(), event.buyerSubject(), event.productId(), event.productCode(), event.paymentId(), event.suspensionRequestEventId(), event.suspensionEventId());
    }
}
