package com.stove.common.web;

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
 *
 * <p>Spring Data 는 {@code Sort} 같은 입력을 엔티티 속성으로 해석하다 이 예외를 던진다.
 * {@link GlobalExceptionHandler} 의 malformed 목록에 없으면 마지막 분기로 흘러 <b>500</b> 이 나간다 —
 * 클라이언트 잘못을 서버 장애로 표시하는 D-015·D-020 과 같은 부류다.
 *
 * <p><b>이것은 안전망이지 본질이 아니다.</b> 본질은 각 엔드포인트가 허용할 키를
 * 스스로 명시하는 것이고(catalog 의 {@code ProductSort}), 그래야 <b>응답 계약의 이름으로</b>
 * 정렬할 수 있다. 여기만 있으면 모르는 이름이 400 이 될 뿐, 계약이 뒤집힌 상태 —
 * 응답에 보이는 이름은 실패하고 내부 이름만 성공하는 상태 — 는 그대로 남는다.
 * 여기가 받는 것은 <b>허용 키를 명시하는 것을 빠뜨린 다음 엔드포인트</b>다.
 *
 * <p><b>왜 클래스를 나눴나</b> — {@code GlobalExceptionHandler} 의 malformed 분기는
 * {@code e.getMessage()} 를 그대로 응답에 싣는다. 이 예외의 메시지는
 * {@code No property 'productId' found for type 'Product'} 라서 그대로 실으면
 * <b>인증 없는 공개 경로로 엔티티 타입명이 나간다.</b> 속성 이름만 돌려주려면 분기가 따로 필요하다.
 *
 * <p>두 번째 이유는 클래스패스다. 스프링 데이터가 없는 실행에서 이 타입을 참조하는 메서드가
 * {@code GlobalExceptionHandler} 안에 있으면 어드바이스를 훑는 순간 기동이 깨진다.
 * 클래스를 나눠 두면 {@link CommonWebAutoConfiguration} 이 조건부로 등록을 건너뛸 수 있다.
 */
/*
 * @Order 가 반드시 있어야 한다.
 *
 * 어드바이스 사이의 선택은 **가장 구체적인 핸들러**가 아니라 **먼저 오는 어드바이스**가 이긴다.
 * GlobalExceptionHandler 에는 Exception.class 를 받는 마지막 분기가 있으므로, 순서를 정하지
 * 않으면 그쪽이 먼저 매칭되어 이 클래스는 한 번도 실행되지 않는다. 등록 순서에 기대는 것도
 * 안 된다 — 자동 구성의 @Bean 메서드 순서가 곧 어드바이스 순서라는 보장이 없다.
 *
 * 실제로 이 순서 없이 커밋할 뻔했다. 클래스 하나만 세운 MockMvc 테스트는 통과했고,
 * 앱에 태워 보고서야 여전히 500 인 것이 드러났다. 그래서 아래 테스트는 두 어드바이스를
 * 함께 세운다(UnknownPropertyExceptionHandlerTest).
 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class UnknownPropertyExceptionHandler {

    @ExceptionHandler(PropertyReferenceException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnknownProperty(PropertyReferenceException e) {
        // 스택 트레이스를 남기지 않는다. 잘못된 요청 하나마다 한 건씩 쌓여
        // 5xx 로그의 신호 대 잡음비를 떨어뜨린다(D-015 와 같은 이유).
        log.warn("unknown property: {}", e.getPropertyName());
        return ResponseEntity.status(ErrorCode.INVALID_REQUEST.status())
                .body(ApiResponse.fail(ErrorCode.INVALID_REQUEST,
                        "알 수 없는 속성입니다: " + e.getPropertyName()));
    }
}
