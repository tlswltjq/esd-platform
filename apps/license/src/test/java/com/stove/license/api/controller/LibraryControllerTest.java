package com.stove.license.api.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.stove.common.web.GlobalExceptionHandler;
import com.stove.license.core.service.LicenseService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 라이브러리 조회의 입구.
 *
 * <p>회원 ID 하나가 <b>누구의 보유 목록을 보여줄지</b>를 결정한다.
 * 이 값이 검증 없이 통과하면 남의 라이브러리를 보게 되는 부류의 사고로 이어지므로,
 * 형식이 어긋난 요청이 서비스에 닿지 않는다는 점을 고정한다.
 */
class LibraryControllerTest {

    private final LicenseService licenseService = mock(LicenseService.class);

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new LibraryController(licenseService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    @DisplayName("[D-015] 회원 헤더가 없으면 400 이다")
    void missingMemberHeaderIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/library"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(licenseService);
    }

    @Test
    @DisplayName("[D-015] 회원 ID 가 숫자가 아니면 400 이다")
    void nonNumericMemberIdIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/library").header("X-Member-Id", "42; DROP TABLE"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(licenseService);
    }

    @Test
    @DisplayName("정상 요청은 그 회원의 라이브러리를 조회한다")
    void validRequestReachesTheService() throws Exception {
        mockMvc.perform(get("/api/v1/library").header("X-Member-Id", 42L))
                .andExpect(status().isOk());

        verify(licenseService).getLibrary(42L);
    }
}
