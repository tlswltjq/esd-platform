package com.stove.common.core.error;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * {@link ErrorCode} 가 지켜야 하는 성질.
 *
 * <p><b>개별 상수의 상태값을 하나씩 적지 않는다.</b> {@code CONFLICT 는 409 다} 같은 단언은
 * 열거형 선언을 옮겨 적은 것에 불과해서, 값이 틀려도 테스트도 같이 틀린 값을 들고 있게 된다.
 *
 * <p>대신 <b>전수로 성질을 건다.</b> 새 코드를 추가할 때 규칙을 어기면 그 순간 깨지는 것이
 * 이 테스트의 존재 이유다 — 상수는 앞으로도 계속 늘어나고, 늘어나는 쪽이 위험하다.
 * 실제 HTTP 응답에 이 상태가 실리는지는 {@code BusinessExceptionStatusTest} 가 본다.
 */
class ErrorCodeTest {

    @Test
    @DisplayName("검사할 코드가 있다 — 이 테스트가 공허해지는 것부터 막는다")
    void enumIsNotEmpty() {
        assertThat(ErrorCode.values()).hasSizeGreaterThan(10);
    }

    @ParameterizedTest
    @EnumSource(ErrorCode.class)
    @DisplayName("모든 코드는 4xx 아니면 5xx 다")
    void everyCodeIsAnErrorStatus(ErrorCode code) {
        // 오류 코드가 2xx·3xx 로 매핑되면 클라이언트는 실패를 성공으로 읽는다.
        // 봉투의 success=false 와 상태코드가 어긋나는 순간 파싱 규칙이 하나로 유지되지 않는다.
        assertThat(code.status().isError())
                .as("%s 가 %s 로 매핑됐다", code, code.status())
                .isTrue();
    }

    @ParameterizedTest
    @EnumSource(ErrorCode.class)
    @DisplayName("모든 코드에 기본 메시지가 있다")
    void everyCodeHasDefaultMessage(ErrorCode code) {
        // 메시지 없이 코드만 나가면 호출한 쪽이 무엇이 잘못됐는지 알 수 없다.
        assertThat(code.defaultMessage()).as("%s", code).isNotBlank();
    }

    @ParameterizedTest
    @EnumSource(ErrorCode.class)
    @DisplayName("이름이 NOT_FOUND 로 끝나면 404 다")
    void notFoundNamesMapTo404(ErrorCode code) {
        // 이름과 상태가 갈라지면 운영에서 코드로 집계한 수치와 상태코드로 집계한 수치가
        // 서로 다른 이야기를 한다. 지금 다섯 개(NOT_FOUND·PRODUCT·ORDER·PAYMENT·LICENSE)가
        // 이 규칙을 지키고 있으므로, 여섯 번째를 다르게 넣는 것을 여기서 막는다.
        if (code.name().endsWith("NOT_FOUND")) {
            assertThat(code.status().value()).as("%s", code).isEqualTo(404);
        }
    }

    @ParameterizedTest
    @EnumSource(ErrorCode.class)
    @DisplayName("이름이 MISMATCH 로 끝나면 409 다")
    void mismatchNamesMapTo409(ErrorCode code) {
        // 대조 실패(가격·결제금액·PG 거래번호)는 요청 형식이 틀린 것이 아니라
        // 서버가 아는 사실과 어긋난 것이다. 400 으로 내보내면 클라이언트가
        // "요청을 고쳐 재시도" 로 읽는데, 실제로는 다시 조회해서 다시 시작해야 한다.
        if (code.name().endsWith("MISMATCH")) {
            assertThat(code.status().value()).as("%s", code).isEqualTo(409);
        }
    }
}
