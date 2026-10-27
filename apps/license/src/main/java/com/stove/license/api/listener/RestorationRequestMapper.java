package com.stove.license.api.listener;

import com.stove.common.kafka.EventEnvelope;
import com.stove.common.kafka.contract.payload.LicenseRestorationRequestedEvent;
import com.stove.license.core.domain.RestorationRequest;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import java.util.Objects;

public final class RestorationRequestMapper {
    private RestorationRequestMapper() { }
    public static RestorationRequest from(EventEnvelope envelope, LicenseRestorationRequestedEvent event) {
        if (envelope == null || event == null || !Objects.equals(envelope.eventId(), event.eventId())
                || !Objects.equals(envelope.eventType(), event.eventType()) || !Objects.equals(envelope.key(), event.orderNo()))
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "복구 봉투·본문·키가 다릅니다.");
        return new RestorationRequest(event.eventId(), event.occurredAt(), event.refundId(), event.attemptId(), event.failureEventId(), event.licenseId(), event.orderNo(), event.buyerSubject(), event.productId(), event.productCode(), event.paymentId(), event.suspensionRequestEventId(), event.suspensionEventId());
    }
}
