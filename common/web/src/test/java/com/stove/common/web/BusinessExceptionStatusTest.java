package com.stove.common.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.stove.common.core.error.BusinessException;
import com.stove.common.core.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@link BusinessException} 이 실제 HTTP 응답에서 {@link ErrorCode#status()} 로 나가는지 본다.
 *
 * <p><b>왜 이 자리가 비어 있었나</b> — 저장소 전체의 단언이 {@code errorCode()} <i>열거값</i>까지만
 * 갔다({@code assertThat(e.errorCode()).isEqualTo(CONFLICT)}). 그 CONFLICT 가 409 로 나가는지를
 * 확인하는 곳은 <b>CI 가 돌리지 않는 셸 스크립트</b>(`scripts/smoke-stack.sh`) 하나였다.
 * 앱 9종의 컨트롤러 테스트도 {@link GlobalExceptionHandler} 를 붙이지만 400 계열(D-015)만 본다.
 *
 * <p>그래서 {@code ErrorCode} 의 상태 매핑을 잘못 바꿔도 {@code ./gradlew build} 는 초록이었다.
 *
 * <p><b>전수로 돈다.</b> 값 몇 개를 집어 단언하면 <i>새로 추가되는</i> 코드는 계속 무방비다.
 * {@code @EnumSource} 라 상수를 추가하는 순간 그 코드도 이 검사를 통과해야 한다 —
 * 커밋된 OpenAPI 스냅샷이나 {@code EventContractTest} 가 목록을 대조하는 것과 같은 성질이다.
 *
 * <p>컨테이너가 필요 없다. 이 검증을 CI 로 되찾아 오는 값이 싼 이유가 여기 있다.
 */
class BusinessExceptionStatusTest {

    /** 오류 코드를 받아 그대로 던지기만 하는 대역. 진짜 검증 대상은 어드바이스다. */
    @RestController
    static class ThrowingController {

        @GetMapping("/boom/{code}")
        String boom(@PathVariable String code) {
            throw new BusinessException(ErrorCode.valueOf(code), "테스트");
        }

        @GetMapping("/unexpected")
        String unexpected() {
            throw new IllegalStateException("아무도 처리하지 않는 예외");
        }
    }

    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new ThrowingController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @ParameterizedTest
    @EnumSource(ErrorCode.class)
    @DisplayName("모든 오류 코드가 선언한 상태로 응답된다")
    void everyCodeIsAnsweredWithItsDeclaredStatus(ErrorCode code) throws Exception {
        mvc.perform(get("/boom/{code}", code.name()))
                .andExpect(status().is(code.status().value()))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value(code.name()));
    }

    @Test
    @DisplayName("스모크가 상태코드로 판정하던 계약 — 대조 실패는 409 다")
    void mismatchesAreConflict() throws Exception {
        // smoke-stack.sh 의 기대-실패 판정 중 상태코드에 기대던 것들이다.
        // 그 스크립트는 CI 밖에 있으므로, 계약 자체는 여기서 지킨다.
        for (ErrorCode code : new ErrorCode[]{
                ErrorCode.PRICE_MISMATCH,
                ErrorCode.PAYMENT_AMOUNT_MISMATCH,
                ErrorCode.PAYMENT_TX_MISMATCH}) {
            mvc.perform(get("/boom/{code}", code.name()))
                    .andExpect(status().isConflict());
        }
    }

    @Test
    @DisplayName("권한 없는 접근은 403 이다")
    void forbiddenIsForbidden() throws Exception {
        // 미보유 회원의 다운로드 티켓 요청이 이 자리다.
        mvc.perform(get("/boom/{code}", ErrorCode.FORBIDDEN.name()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("처리되지 않은 예외는 500 이고, 원인 메시지를 밖으로 흘리지 않는다")
    void unexpectedIsInternalErrorWithoutLeakingCause() throws Exception {
        // 이 분기는 어느 앱 테스트에서도 실행되지 않고 있었다.
        // 예외 메시지를 그대로 내보내면 내부 구조가 응답으로 샌다.
        mvc.perform(get("/unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value(ErrorCode.INTERNAL_ERROR.name()))
                .andExpect(jsonPath("$.error.message")
                        .value(ErrorCode.INTERNAL_ERROR.defaultMessage()));
    }
}
