package com.stove.common.web;

import com.stove.common.core.error.EchoedInput;
import com.stove.common.core.error.ErrorCode;
import com.stove.common.core.response.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.mapping.PropertyReferenceException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 존재하지 않는 속성으로 정렬·조회를 요청한 경우의 안전망. [D-024]
 * 본질은 각 엔드포인트가 허용 키를 명시하는 것이고(catalog 의 {@code ProductSort}),
 * 여기는 그것을 빠뜨린 다음 엔드포인트를 받는다.
 *
 * <p><b>{@code @Order} 를 지우면 이 클래스는 한 번도 실행되지 않는다</b> —
 * 어드바이스는 가장 구체적인 핸들러가 아니라 먼저 오는 쪽이 이긴다.
 *
 * <p>클래스를 나눈 이유, 로그와 응답에 각각 무엇을 싣는지(D-025)는 docs/code-notes.md
 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class UnknownPropertyExceptionHandler {

    @ExceptionHandler(PropertyReferenceException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnknownProperty(PropertyReferenceException e) {
        // 스택 트레이스는 남기지 않고(D-015), 타입명은 로그에만 남긴다(D-025).
        log.warn("unknown property: {} (type: {})",
                EchoedInput.safe(e.getPropertyName()), e.getType().getType().getName());
        return ResponseEntity.status(ErrorCode.INVALID_REQUEST.status())
                .body(ApiResponse.fail(ErrorCode.INVALID_REQUEST,
                        // 응답에는 속성 이름만, 되싣기 안전한 형태로만.
                        "알 수 없는 속성입니다: " + EchoedInput.safe(e.getPropertyName())));
    }
}
