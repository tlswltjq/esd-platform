package com.stove.license.core.domain;

import com.stove.common.web.error.ApiErrorCode;

public enum LicenseErrorCode implements ApiErrorCode {
    PRODUCT_ALREADY_OWNED(409, "이미 보유한 상품입니다. 구매분은 보관함에 남습니다."),
    PURCHASE_NOT_REGISTRABLE(409, "등록할 수 있는 보관함 항목이 아닙니다.");

    private final int status;
    private final String message;
    LicenseErrorCode(int status, String message) { this.status = status; this.message = message; }
    public String code() { return name(); }
    public int statusCode() { return status; }
    public String defaultMessage() { return message; }
}
