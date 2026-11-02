package com.stove.catalog.core.domain;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;

public final class CatalogSalesValues {
    private CatalogSalesValues() {}
    public static void requireCode(String code) {
        if (code==null || !code.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,49}"))
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "상품 코드가 올바르지 않습니다.");
    }
    public static void requireActor(String actor) {
        if (actor==null || actor.isBlank() || actor.length()>100)
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "변경 주체가 올바르지 않습니다.");
    }
}
