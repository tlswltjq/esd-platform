package com.stove.common.web.error;

/** 도메인 규칙 위반. GlobalExceptionHandler 가 ErrorCode 의 HTTP 상태로 변환한다. */
public class BusinessException extends RuntimeException {

    private final ApiErrorCode errorCode;

    public BusinessException(ApiErrorCode errorCode) {
        this(errorCode, errorCode.defaultMessage());
    }

    public BusinessException(ApiErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ApiErrorCode errorCode() {
        return errorCode;
    }
}
