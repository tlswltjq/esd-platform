package com.stove.common.core.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.stove.common.core.error.ErrorCode;

/**
 * 전 서비스 공통 응답 봉투. 성공/실패 형태를 동일하게 유지해
 * 게이트웨이·프론트·운영도구가 하나의 파싱 규칙만 쓰도록 한다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(boolean success, T data, ErrorBody error) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null);
    }

    public static ApiResponse<Void> ok() {
        return new ApiResponse<>(true, null, null);
    }

    public static ApiResponse<Void> fail(ErrorCode code, String message) {
        return new ApiResponse<>(false, null, new ErrorBody(code.name(), message));
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ErrorBody(String code, String message) {
    }
}
