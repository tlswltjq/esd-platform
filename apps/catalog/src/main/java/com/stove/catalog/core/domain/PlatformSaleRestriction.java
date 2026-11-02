package com.stove.catalog.core.domain;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;

public record PlatformSaleRestriction(String productCode, boolean restricted, long expectedCatalogVersion) {
    public PlatformSaleRestriction {
        CatalogSalesValues.requireCode(productCode);
        if (expectedCatalogVersion<=0) throw new BusinessException(ErrorCode.INVALID_REQUEST, "현재 상품 버전이 필요합니다.");
    }
}
