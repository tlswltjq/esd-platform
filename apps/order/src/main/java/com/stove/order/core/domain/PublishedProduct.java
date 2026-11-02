package com.stove.order.core.domain;

import com.stove.common.web.error.BusinessException;

/** Catalog facts for ordering and admission, independent of the public HTTP page. */
public record PublishedProduct(Long productId, String productCode, String title, long contentVersion,
                               long price, String currency, String saleStatus, boolean publicDetailAllowed) {
    public PublishedProduct {
        if (productId == null || productId <= 0 || productCode == null
                || !productCode.matches(OrderValues.PRODUCT_CODE_PATTERN)
                || title == null || title.isBlank() || title.length() > 200
                || contentVersion <= 0 || price < 0 || !"KRW".equals(currency)
                || (!"ON_SALE".equals(saleStatus) && !"STOPPED".equals(saleStatus) && !"BLOCKED".equals(saleStatus)))
            throw new BusinessException(OrderErrorCode.CATALOG_INVALID_RESPONSE);
    }

    public PublishedProduct(Long productId, String productCode, String title, long contentVersion,
            long price, String currency, String saleStatus) {
        this(productId, productCode, title, contentVersion, price, currency, saleStatus, true);
    }

    public PublishedProduct(Long productId, String productCode, String title, long contentVersion,
            long price, String currency) {
        this(productId, productCode, title, contentVersion, price, currency, "ON_SALE");
    }
}
