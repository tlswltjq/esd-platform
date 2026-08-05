package com.stove.download.api.controller;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.stove.common.web.GlobalExceptionHandler;
import com.stove.download.core.domain.DownloadTicket;
import com.stove.download.core.service.DownloadService;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 다운로드 티켓 발급의 입구. <b>보유 여부를 판정하는 유일한 입력이 회원 ID</b>다.
 *
 * <p>{@code X-Member-Id} 가 없으면 "누가 요청했는지 모르는 상태"인데,
 * 그대로 서비스까지 내려가면 판정 자체가 성립하지 않는다.
 * 요청이 서비스에 닿기 전에 끝나야 한다는 점을 고정한다.
 */
class DownloadControllerTest {

    private static final DownloadTicket TICKET = new DownloadTicket(
            "GAME-001", "1.0.0", 1_073_741_824L, "a1b2c3",
            "https://cdn.stove.test/games/1/1.0.0.pak", Instant.parse("2026-01-01T00:00:00Z"));

    private final DownloadService downloadService = mock(DownloadService.class);

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new DownloadController(downloadService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    @DisplayName("[D-015] 회원 헤더 없는 티켓 요청은 400 이다 — 소유 판정의 입력이 없다")
    void ticketWithoutMemberHeaderIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/downloads/GAME-001/ticket"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(downloadService);
    }

    @Test
    @DisplayName("[D-015] 회원 ID 가 숫자가 아니면 400 이다")
    void nonNumericMemberIdIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/downloads/GAME-001/ticket")
                        .header("X-Member-Id", "not-a-number"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(downloadService);
    }

    @Test
    @DisplayName("정상 요청은 상품코드와 회원 ID 로 서비스에 넘어가고 200 으로 응답한다")
    void validTicketRequestReachesTheService() throws Exception {
        // 대역이 null 을 돌려주면 DownloadTicketResponse.from 에서 NPE 가 나 실제로는 500 이다.
        // 상태 단언이 없던 동안 이 테스트는 그 500 을 통과로 세고 있었다.
        when(downloadService.issueTicket("GAME-001", 42L)).thenReturn(TICKET);

        mockMvc.perform(get("/api/v1/downloads/GAME-001/ticket")
                        .header("X-Member-Id", 42L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.productCode").value("GAME-001"))
                .andExpect(jsonPath("$.data.version").value("1.0.0"))
                .andExpect(jsonPath("$.data.downloadUrl").value("https://cdn.stove.test/games/1/1.0.0.pak"));

        verify(downloadService).issueTicket("GAME-001", 42L);
    }

    @Test
    @DisplayName("매니페스트 조회는 인증 헤더 없이도 열려 있다 — 패치 이력은 보유와 무관하다")
    void manifestLookupNeedsNoMemberHeader() throws Exception {
        mockMvc.perform(get("/api/v1/downloads/GAME-001/manifests"))
                .andExpect(status().isOk());

        verify(downloadService).getManifests(anyString());
        verify(downloadService, org.mockito.Mockito.never()).issueTicket(anyString(), anyLong());
    }
}
