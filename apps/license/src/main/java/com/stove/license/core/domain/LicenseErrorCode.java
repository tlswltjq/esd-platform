package com.stove.license.core.domain;

import com.stove.common.web.error.ApiErrorCode;

/** license 업무 오류. 코드와 HTTP 상태는 기존 API 계약을 유지한다. */
public enum LicenseErrorCode implements ApiErrorCode {

    LICENSE_NOT_FOUND(404, "라이선스를 찾을 수 없습니다.");

    private final int statusCode;
    private final String defaultMessage;

    LicenseErrorCode(int statusCode, String defaultMessage) {
        this.statusCode = statusCode;
        this.defaultMessage = defaultMessage;
    }

    @Override
    public String code() { return name(); }

    @Override
    public int statusCode() { return statusCode; }

    @Override
    public String defaultMessage() { return defaultMessage; }
}
