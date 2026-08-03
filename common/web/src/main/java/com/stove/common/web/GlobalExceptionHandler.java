package com.stove.common.web;

import com.stove.common.core.error.BusinessException;
import com.stove.common.core.error.ErrorCode;
import com.stove.common.core.response.ApiResponse;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 전 서비스 공통 예외 → ApiResponse 변환.
 * 5xx 만 stack trace 를 남겨 운영 로그의 노이즈를 줄인다.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException e) {
        ErrorCode code = e.errorCode();
        if (code.status().is5xxServerError()) {
            log.error("business error: {}", code, e);
        } else {
            log.warn("business error: {} - {}", code, e.getMessage());
        }
        return ResponseEntity.status(code.status()).body(ApiResponse.fail(code, e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .orElse(ErrorCode.INVALID_REQUEST.defaultMessage());
        return ResponseEntity.status(ErrorCode.INVALID_REQUEST.status())
                .body(ApiResponse.fail(ErrorCode.INVALID_REQUEST, message));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraint(ConstraintViolationException e) {
        return ResponseEntity.status(ErrorCode.INVALID_REQUEST.status())
                .body(ApiResponse.fail(ErrorCode.INVALID_REQUEST, e.getMessage()));
    }

    /**
     * 요청 자체가 형식을 못 갖춘 경우 — 헤더/파라미터 누락, 타입 불일치, 깨진 본문.
     *
     * <p>여기가 없으면 전부 {@link #handleUnexpected} 로 흘러 <b>500</b> 이 나간다(D-015).
     * 클라이언트 잘못을 서버 장애로 표시하면 두 가지가 망가진다 —
     * 재시도해도 소용없는 요청을 클라이언트가 재시도하고, 5xx 알람이 서버 장애로 울린다.
     *
     * <p>스택 트레이스도 남기지 않는다. 이 부류는 잘못된 요청 하나마다 한 건씩 쌓이므로
     * 5xx 로그의 신호 대 잡음비를 떨어뜨린다.
     */
    @ExceptionHandler({
            MissingRequestHeaderException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class
    })
    public ResponseEntity<ApiResponse<Void>> handleMalformedRequest(Exception e) {
        log.warn("malformed request: {}", e.getMessage());
        return ResponseEntity.status(ErrorCode.INVALID_REQUEST.status())
                .body(ApiResponse.fail(ErrorCode.INVALID_REQUEST, e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception e) {
        log.error("unhandled exception", e);
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.status())
                .body(ApiResponse.fail(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.defaultMessage()));
    }
}
