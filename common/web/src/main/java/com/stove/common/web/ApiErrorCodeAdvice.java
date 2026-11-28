package com.stove.common.web;

import com.stove.common.web.response.ApiResponse;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * 실패 응답({@link ApiResponse#error()})의 오류 코드를 요청에 남긴다. {@link ApiErrorObservationConvention} 이 그 값을 지표 태그로 싣는다.
 *
 * <p>예외 처리기마다 적지 않고 본문을 쓰는 한 곳에서 읽는다 — 공통 처리기뿐 아니라 앱이 둔 처리기와 컨트롤러가 직접 돌려준
 * 실패 응답도 같은 길을 지난다. 필터가 직접 쓴 응답(인증 필터의 401 등)은 이 길을 지나지 않아 코드 없이 상태로만 남는다.
 */
@RestControllerAdvice
public class ApiErrorCodeAdvice implements ResponseBodyAdvice<Object> {

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        if (body instanceof ApiResponse<?> failed && failed.error() != null
                && request instanceof ServletServerHttpRequest servlet) {
            servlet.getServletRequest().setAttribute(ApiErrorObservationConvention.REQUEST_ATTRIBUTE, failed.error().code());
        }
        return body;
    }
}
