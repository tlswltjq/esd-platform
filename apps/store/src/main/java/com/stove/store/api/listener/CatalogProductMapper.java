package com.stove.store.api.listener;

import com.stove.common.kafka.EventEnvelope;
import com.stove.common.kafka.contract.payload.*;
import com.stove.common.web.error.*;
import java.util.Objects;

public final class CatalogProductMapper {
    private CatalogProductMapper() {}

    public static CatalogProductSnapshot from(EventEnvelope envelope, CatalogProductPublishedEvent event) {
        if (envelope == null || event == null || event.eventId() == null || event.eventId().isBlank()
                || event.eventId().length() > 64
                || !Objects.equals(envelope.eventId(), event.eventId())
                || !Objects.equals(envelope.eventType(), event.eventType())
                || event.occurredAt() == null || event.product() == null
                || !Objects.equals(envelope.key(), event.product().productCode()))
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "공개 상품 이벤트 계약이 올바르지 않습니다.");
        return event.product();
    }
}
