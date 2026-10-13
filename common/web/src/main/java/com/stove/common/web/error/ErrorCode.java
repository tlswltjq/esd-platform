package com.stove.common.web.error;

import org.springframework.http.HttpStatus;

/** 모든 API에서 의미가 같은 일반 오류만 정의한다. */
public enum ErrorCode implements ApiErrorCode {

    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "요청이 올바르지 않습니다."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "권한이 없습니다."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "대상을 찾을 수 없습니다."),
    CONFLICT(HttpStatus.CONFLICT, "현재 상태에서 처리할 수 없습니다."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "일시적인 오류가 발생했습니다."),
    UPSTREAM_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "연동 서비스 응답이 없습니다.");


    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    @Override
    public String code() { return name(); }

    public HttpStatus status() { return status; }

    @Override
    public int statusCode() { return status.value(); }

    @Override
    public String defaultMessage() { return defaultMessage; }
}
