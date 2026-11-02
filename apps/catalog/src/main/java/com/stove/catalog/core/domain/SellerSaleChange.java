package com.stove.catalog.core.domain;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;

public record SellerSaleChange(String productCode, String saleStatus, long expectedCatalogVersion) {
    public SellerSaleChange {
        CatalogSalesValues.requireCode(productCode);
        if ((!"ON_SALE".equals(saleStatus) && !"STOPPED".equals(saleStatus)) || expectedCatalogVersion<=0)
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "판매 상태와 현재 버전이 필요합니다.");
    }
}
