package com.stove.license.api.listener;

import com.stove.common.contract.EventMessage;
import com.stove.common.contract.payload.LicenseSuspensionRequestedEvent;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.license.core.domain.SuspensionRequest;
import java.util.Objects;

public final class LicenseSuspensionMapper {
    private LicenseSuspensionMapper() {}

    public static SuspensionRequest from(EventMessage message, LicenseSuspensionRequestedEvent event) {
        if (message == null || event == null || !Objects.equals(message.eventId(), event.eventId())
                || !Objects.equals(message.eventType(), event.eventType()) || !Objects.equals(message.key(), event.orderNo())) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "정지 요청 헤더와 본문이 다릅니다.");
        }
        return new SuspensionRequest(event.eventId(), event.occurredAt(), event.refundId(), event.orderNo(),
                event.buyerSubject(), event.productId(), event.productCode(), event.paymentId());
    }
}
