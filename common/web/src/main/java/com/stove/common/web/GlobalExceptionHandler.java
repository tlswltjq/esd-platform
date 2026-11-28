package com.stove.common.web;

import com.stove.common.web.error.ApiErrorCode;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.common.web.response.ApiResponse;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * 전 서비스 공통 예외 → ApiResponse 변환.
 * 5xx 만 stack trace 를 남겨 운영 로그의 노이즈를 줄인다.
 *
 * <p>Spring MVC 가 던지는 표준 예외의 상태 코드는 {@link ResponseEntityExceptionHandler} 가 정하고, 여기서는 본문만
 * {@link ApiResponse} 로 바꾼다(결정 mvc-standard-exceptions). {@link #handleUnexpected} 가 있으면 따로 받지 않은 MVC 예외가 전부
 * 500 이 되므로(D-015), 예외를 하나씩 나열하지 않고 상위 클래스의 목록에 맡긴다.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException e) {
        ApiErrorCode code = e.errorCode();
        if (code.statusCode() >= 500) {
            log.error("business error: {}", code, e);
        } else {
            log.warn("business error: {} - {}", code, e.getMessage());
        }
        return ResponseEntity.status(code.statusCode()).body(ApiResponse.fail(code, e.getMessage()));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraint(ConstraintViolationException e) {
        return ResponseEntity.status(ErrorCode.INVALID_REQUEST.status())
                .body(ApiResponse.fail(ErrorCode.INVALID_REQUEST, e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception e) {
        log.error("unhandled exception", e);
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.status())
                .body(ApiResponse.fail(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.defaultMessage()));
    }

    /**
     * 표준 MVC 예외의 본문을 {@link ApiResponse} 로 바꾼다. 상위 클래스의 모든 처리가 이 메서드를 거친다.
     *
     * <p>Spring 이 5xx 로 정한 것(변환기 없음, 응답 쓰기 실패 등)은 서버 문제라 {@link #handleUnexpected} 와 같이 답한다.
     * 4xx 는 클라이언트 잘못이라 스택 트레이스를 남기지 않는다 — 잘못된 요청 하나마다 쌓이면 5xx 로그가 묻힌다(D-015).
     * 상위 클래스가 채운 헤더(405 의 {@code Allow} 등)는 그대로 싣는다.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception e, Object body, HttpHeaders headers,
                                                             HttpStatusCode statusCode, WebRequest request) {
        if (statusCode.is5xxServerError()) {
            log.error("unhandled exception", e);
            return envelope(e, ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.defaultMessage(), headers, request);
        }
        ErrorCode code = clientErrorOf(statusCode);
        return envelope(e, code, clientMessage(e, code), headers, request);
    }

    private ResponseEntity<Object> envelope(Exception e, ErrorCode code, String message, HttpHeaders headers,
                                            WebRequest request) {
        return super.handleExceptionInternal(e, ApiResponse.fail(code, message), headers, code.status(), request);
    }

    /** Spring 이 정한 4xx 상태에 맞는 일반 오류 코드. 따로 둔 코드가 없는 4xx 는 잘못된 요청으로 답한다. */
    private static ErrorCode clientErrorOf(HttpStatusCode statusCode) {
        HttpStatus status = HttpStatus.resolve(statusCode.value());
        if (status == null) {
            return ErrorCode.INVALID_REQUEST;
        }
        return switch (status) {
            case NOT_FOUND -> ErrorCode.NOT_FOUND;
            case METHOD_NOT_ALLOWED -> ErrorCode.METHOD_NOT_ALLOWED;
            case NOT_ACCEPTABLE -> ErrorCode.NOT_ACCEPTABLE;
            case PAYLOAD_TOO_LARGE -> ErrorCode.PAYLOAD_TOO_LARGE;
            case UNSUPPORTED_MEDIA_TYPE -> ErrorCode.UNSUPPORTED_MEDIA_TYPE;
            default -> ErrorCode.INVALID_REQUEST;
        };
    }

    /**
     * 무엇을 고칠지 알려 주는 메시지. 본문 검증은 첫 필드 오류를, 헤더·파라미터 누락과 타입 불일치·깨진 본문은 Spring 의
     * 설명을 싣는다. 그 밖은 코드의 기본 메시지다.
     */
    private static String clientMessage(Exception e, ErrorCode code) {
        if (e instanceof MethodArgumentNotValidException invalid) {
            return invalid.getBindingResult().getFieldErrors().stream()
                    .findFirst()
                    .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                    .orElse(code.defaultMessage());
        }
        if (e instanceof MissingRequestHeaderException
                || e instanceof MissingServletRequestParameterException
                || e instanceof MethodArgumentTypeMismatchException
                || e instanceof HttpMessageNotReadableException) {
            log.warn("malformed request: {}", e.getMessage());
            return e.getMessage();
        }
        return code.defaultMessage();
    }
}
