package com.stove.order.api.listener;

import com.stove.common.kafka.EventEnvelope;
import com.stove.common.kafka.contract.payload.LicenseGrantedEvent;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.order.core.domain.LicenseGrant;
import java.util.Objects;

public final class LicenseGrantMapper {
    private LicenseGrantMapper() {}

    public static LicenseGrant from(EventEnvelope envelope, LicenseGrantedEvent event) {
        if (envelope == null || event == null || !Objects.equals(envelope.eventId(), event.eventId())
                || !Objects.equals(envelope.eventType(), event.eventType())
                || !Objects.equals(envelope.key(), event.orderNo())) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "지급 확인 봉투와 본문이 다릅니다.");
        }
        return new LicenseGrant(event.eventId(), event.occurredAt(), event.licenseId(), event.orderNo(),
                event.buyerSubject(), event.productId(), event.productCode(), event.paymentId());
    }
}
