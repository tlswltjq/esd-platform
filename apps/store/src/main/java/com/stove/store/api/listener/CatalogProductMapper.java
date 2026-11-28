package com.stove.store.api.listener;

import com.stove.common.contract.EventMessage;
import com.stove.common.contract.payload.CatalogProductPublishedEvent;
import com.stove.common.contract.payload.CatalogProductSnapshot;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.store.core.domain.StoreProductValues;
import java.util.Objects;

public final class CatalogProductMapper {
    private CatalogProductMapper() {}

    public static CatalogProductSnapshot from(EventMessage message, CatalogProductPublishedEvent event) {
        if (message == null || event == null || event.eventId() == null || event.eventId().isBlank()
                || event.eventId().length() > 64
                || !Objects.equals(message.eventId(), event.eventId())
                || !Objects.equals(message.eventType(), event.eventType())
                || event.occurredAt() == null || event.product() == null
                || !Objects.equals(message.key(), event.product().productCode())
                || (!"ON_SALE".equals(event.product().saleStatus()) && !"STOPPED".equals(event.product().saleStatus())
                    && !"BLOCKED".equals(event.product().saleStatus()))
                || event.product().catalogVersion() < event.product().contentVersion())
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "공개 상품 이벤트 계약이 올바르지 않습니다.");
        StoreProductValues.requireSnapshot(event.product());
        return event.product();
    }
}
