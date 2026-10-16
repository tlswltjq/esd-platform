package com.stove.order.core.domain;

import com.stove.common.web.error.*;

public record OrderRequest(String requestId, String productCode, long expectedContentVersion, long expectedAmount) {
    public static final String UUID_PATTERN = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
    public static final String PRODUCT_CODE_PATTERN = "[A-Za-z0-9][A-Za-z0-9._-]{0,49}";

    public OrderRequest {
        if (requestId == null || !requestId.matches(UUID_PATTERN)
                || productCode == null || !productCode.matches(PRODUCT_CODE_PATTERN)
                || expectedContentVersion <= 0 || expectedAmount < 0)
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
    }
}
