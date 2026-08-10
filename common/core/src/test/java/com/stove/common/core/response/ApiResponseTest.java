package com.stove.common.core.response;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.core.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 전 서비스가 공유하는 응답 봉투의 <b>직렬화 계약</b>.
 *
 * <p>필드 이름과 존재 여부를 직접 확인하는 이유가 있다. 이 봉투는 자바 타입으로만 소비되지 않는다 —
 * 스모크 스크립트가 {@code grep '"success":true'} 로 판정하고, 게이트웨이·프론트·운영도구가
 * 하나의 파싱 규칙을 쓴다. 레코드 컴포넌트 이름을 바꾸거나 {@code @JsonInclude} 를 떼면
 * <b>자바 쪽은 전부 통과하는데 바깥이 조용히 깨진다.</b>
 */
class ApiResponseTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("실패 응답은 코드 이름을 싣는다 — ordinal 이 아니라")
    void failCarriesEnumName() throws Exception {
        // 열거형 순서는 계약이 아니다. 상수를 중간에 하나 끼워 넣으면 ordinal 은 전부 밀리는데,
        // 소비자가 보는 값은 그대로여야 한다.
        String json = mapper.writeValueAsString(
                ApiResponse.fail(ErrorCode.PRICE_MISMATCH, "가격이 변경되었습니다"));

        assertThat(json).contains("\"code\":\"PRICE_MISMATCH\"");
        assertThat(json).contains("\"success\":false");
    }

    @Test
    @DisplayName("실패 응답에는 data 키가 없다")
    void failOmitsDataKey() throws Exception {
        // @JsonInclude(NON_NULL) 이 떨어지면 "data":null 이 붙는다. 그 자체로 사고는 아니지만
        // 소비자가 data 의 존재로 성공을 판정하고 있으면 그때 갈린다.
        String json = mapper.writeValueAsString(ApiResponse.fail(ErrorCode.NOT_FOUND, "없다"));

        assertThat(json).doesNotContain("\"data\"");
    }

    @Test
    @DisplayName("성공 응답에는 error 키가 없다")
    void okOmitsErrorKey() throws Exception {
        String json = mapper.writeValueAsString(ApiResponse.ok("결과"));

        assertThat(json).contains("\"success\":true");
        assertThat(json).contains("\"data\":\"결과\"");
        assertThat(json).doesNotContain("\"error\"");
    }

    @Test
    @DisplayName("데이터 없는 성공도 success=true 다")
    void okWithoutDataIsStillSuccess() throws Exception {
        // 스모크의 부재 판정(lacks)이 이 형태에 의존한다 — 봉투를 먼저 보고
        // 응답 자체가 실패한 것을 '없어졌다' 로 착각하지 않으려는 장치다.
        String json = mapper.writeValueAsString(ApiResponse.ok());

        assertThat(json).contains("\"success\":true");
        assertThat(json).doesNotContain("\"data\"");
        assertThat(json).doesNotContain("\"error\"");
    }
}
