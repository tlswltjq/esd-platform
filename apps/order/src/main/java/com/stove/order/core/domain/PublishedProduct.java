package com.stove.order.core.domain;

import com.stove.common.web.error.BusinessException;

/** The public facts needed for this order, independent of the Catalog HTTP DTO. */
public record PublishedProduct(Long productId, String productCode, String title, long contentVersion,
                               long price, String currency) {
    public PublishedProduct {
        if (productId == null || productId <= 0 || productCode == null
                || !productCode.matches(OrderValues.PRODUCT_CODE_PATTERN)
                || title == null || title.isBlank() || title.length() > 200
                || contentVersion <= 0 || price < 0 || !"KRW".equals(currency))
            throw new BusinessException(OrderErrorCode.CATALOG_INVALID_RESPONSE);
    }
}
