package com.stove.order.api.listener;

import com.stove.common.contract.EventMessage;
import com.stove.common.contract.payload.LicenseGrantedEvent;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.order.core.domain.LicenseGrant;
import java.util.Objects;

public final class LicenseGrantMapper {
    private LicenseGrantMapper() {}

    public static LicenseGrant from(EventMessage message, LicenseGrantedEvent event) {
        if (message == null || event == null || !Objects.equals(message.eventId(), event.eventId())
                || !Objects.equals(message.eventType(), event.eventType())
                || !Objects.equals(message.key(), event.orderNo())) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "지급 확인 헤더와 본문이 다릅니다.");
        }
        return new LicenseGrant(event.eventId(), event.occurredAt(), event.licenseId(), event.orderNo(),
                event.buyerSubject(), event.productId(), event.productCode(), event.paymentId());
    }
}
