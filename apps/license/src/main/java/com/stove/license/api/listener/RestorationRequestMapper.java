package com.stove.license.api.listener;

import com.stove.common.contract.EventMessage;
import com.stove.common.contract.payload.LicenseRestorationRequestedEvent;
import com.stove.license.core.domain.RestorationRequest;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import java.util.Objects;

public final class RestorationRequestMapper {
    private RestorationRequestMapper() { }
    public static RestorationRequest from(EventMessage message, LicenseRestorationRequestedEvent event) {
        if (message == null || event == null || !Objects.equals(message.eventId(), event.eventId())
                || !Objects.equals(message.eventType(), event.eventType()) || !Objects.equals(message.key(), event.orderNo()))
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "복구 헤더·본문·키가 다릅니다.");
        return new RestorationRequest(event.eventId(), event.occurredAt(), event.refundId(), event.attemptId(), event.failureEventId(), event.licenseId(), event.orderNo(), event.buyerSubject(), event.productId(), event.productCode(), event.paymentId(), event.suspensionRequestEventId(), event.suspensionEventId());
    }
}
