package com.stove.license.api.listener;

import com.stove.common.kafka.EventEnvelope;
import com.stove.common.kafka.contract.payload.LicenseSuspensionRequestedEvent;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.license.core.domain.SuspensionRequest;
import java.util.Objects;

public final class LicenseSuspensionMapper {
    private LicenseSuspensionMapper() {}

    public static SuspensionRequest from(EventEnvelope envelope, LicenseSuspensionRequestedEvent event) {
        if (envelope == null || event == null || !Objects.equals(envelope.eventId(), event.eventId())
                || !Objects.equals(envelope.eventType(), event.eventType()) || !Objects.equals(envelope.key(), event.orderNo())) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "정지 요청 봉투와 본문이 다릅니다.");
        }
        return new SuspensionRequest(event.eventId(), event.occurredAt(), event.refundId(), event.orderNo(),
                event.buyerSubject(), event.productId(), event.productCode(), event.paymentId());
    }
}
