package com.stove.common.web;

import com.stove.common.web.error.ErrorCode;
import com.stove.common.web.response.ApiResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** MVC에서 발생한 인증·권한 실패의 응답 변환. 필터의 접근 정책은 각 앱이 소유한다. */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class SecurityExceptionHandler {

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiResponse<Void>> authentication(AuthenticationException exception) {
        return response(ErrorCode.UNAUTHORIZED);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> accessDenied(AccessDeniedException exception) {
        return response(ErrorCode.FORBIDDEN);
    }

    private ResponseEntity<ApiResponse<Void>> response(ErrorCode code) {
        return ResponseEntity.status(code.status())
                .body(ApiResponse.fail(code, code.defaultMessage()));
    }
}
