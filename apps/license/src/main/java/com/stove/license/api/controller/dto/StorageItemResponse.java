package com.stove.license.api.controller.dto;

import com.stove.license.core.domain.PurchaseStatus;
import com.stove.license.core.service.StorageItem;
import java.time.Instant;

/** 보관함 항목 한 개. registrable은 지금 라이브러리에 등록할 수 있는지다(결정 93). */
public record StorageItemResponse(String licenseId, String orderNo, Long productId, String productCode, String title,
        Instant purchasedAt, PurchaseStatus status, boolean registrable) {
    public static StorageItemResponse from(StorageItem item) {
        return new StorageItemResponse(item.purchase().getLicenseId(), item.purchase().getOrderNo(), item.purchase().getProductId(),
                item.purchase().getProductCode(), item.purchase().getTitle(), item.purchase().getAcquiredAt(),
                item.purchase().getStatus(), item.registrable());
    }
}
