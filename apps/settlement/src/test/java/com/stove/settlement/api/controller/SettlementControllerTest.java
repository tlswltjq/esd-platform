package com.stove.settlement.api.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.stove.common.web.GlobalExceptionHandler;
import com.stove.settlement.core.service.SettlementService;
import java.time.YearMonth;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 정산 운영툴의 입구 검증. <b>마감은 되돌릴 수 없는 조작</b>이다.
 *
 * <p>{@code month} 파라미터가 조작 대상을 정한다. 이 값이 누락되거나 형식이 틀린 채로
 * 통과하면 의도하지 않은 달이 마감되고, 그 뒤 도착한 원장은 D-001 의 상황이 된다 —
 * 마감된 달에 붙은 금액이 어느 확정본에도 안 들어간다.
 *
 * <p>D-015 수정 전에는 이 부류가 전부 500 이었다. 운영툴 사용자가
 * "서버 오류"를 보고 다시 눌러 보는 상황이 나올 수 있었다.
 */
class SettlementControllerTest {

    private final SettlementService settlementService = mock(SettlementService.class);

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new SettlementController(settlementService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    @DisplayName("[D-015] 마감 월이 없으면 400 이다 — 어느 달을 마감할지 모르는 요청")
    void closeWithoutMonthIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/settlements/close"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(settlementService);
    }

    @Test
    @DisplayName("[D-015] 마감 월 형식이 틀리면 400 이다")
    void closeWithMalformedMonthIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/settlements/close").param("month", "2026-13-99"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(settlementService);
    }

    @Test
    @DisplayName("정상 마감 요청은 해당 월로 넘어간다")
    void validCloseReachesTheService() throws Exception {
        mockMvc.perform(post("/api/v1/settlements/close").param("month", "2026-08"))
                .andExpect(status().isOk());

        verify(settlementService).closeMonth(YearMonth.of(2026, 8));
    }

    @Test
    @DisplayName("[D-015] 조회도 월 형식이 틀리면 400 이다")
    void malformedMonthOnQueryIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/settlements/closings").param("month", "not-a-month"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(settlementService);
    }

    @Test
    @DisplayName("[D-015] 판매자 ID 가 숫자가 아니면 400 이다")
    void nonNumericSellerIdIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/settlements/sellers/abc").param("month", "2026-08"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(settlementService);
    }
}
