package com.stove.catalog.core.domain;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;

public final class ListingValues {
    private ListingValues() {}
    public static void requireProductCode(String code) {
        if (code == null || !code.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,49}")) throw new BusinessException(ErrorCode.INVALID_REQUEST, "Invalid product code");
    }
    public static void requireActor(String actor) {
        if (actor == null || actor.isBlank() || actor.length() > 100) throw new BusinessException(ErrorCode.INVALID_REQUEST, "Invalid listing actor");
    }
}
