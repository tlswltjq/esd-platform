package com.stove.order.api.listener;

import com.stove.common.kafka.EventEnvelope;
import com.stove.common.kafka.contract.payload.LicenseSuspendedEvent;
import com.stove.order.core.domain.LicenseSuspensionConfirmation;
import com.stove.order.core.domain.RefundCompletionRejected;
import java.util.Objects;

public final class RefundSuspensionMapper {
    private RefundSuspensionMapper() {}

    public static LicenseSuspensionConfirmation from(EventEnvelope envelope, LicenseSuspendedEvent event) {
        if (envelope == null || event == null || !Objects.equals(envelope.eventId(), event.eventId())
                || !Objects.equals(envelope.eventType(), event.eventType()) || !Objects.equals(envelope.key(), event.orderNo())) {
            throw new RefundCompletionRejected("정지 완료 봉투와 본문이 다릅니다.");
        }
        return new LicenseSuspensionConfirmation(event.eventId(), event.occurredAt(), event.requestEventId(),
                event.refundId(), event.licenseId(), event.orderNo(), event.buyerSubject(), event.productId(),
                event.productCode(), event.paymentId());
    }
}
