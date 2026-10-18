package com.stove.order.core.domain;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;

public record OrderRequest(String requestId, String productCode, long expectedContentVersion, long expectedAmount) {
    public OrderRequest {
        if (requestId == null || !requestId.matches(OrderValues.UUID_PATTERN)
                || productCode == null || !productCode.matches(OrderValues.PRODUCT_CODE_PATTERN)
                || expectedContentVersion <= 0 || expectedAmount < 0)
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
    }
}
