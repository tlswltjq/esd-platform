package com.stove.store.api.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.stove.common.web.GlobalExceptionHandler;
import com.stove.store.core.domain.ProductSearchRepository;
import com.stove.store.core.service.StoreService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 진열/검색의 입구. 인증이 필요 없는 <b>완전 공개 경로</b>다.
 *
 * <p>그래서 기본값이 중요하다. 페이지 크기에 기본값이 없거나 조용히 커지면
 * 누구나 파라미터 하나로 대량 조회를 걸 수 있고, 이 서비스는 읽기 트래픽이
 * 가장 몰리는 구간이다. 기본값이 무엇인지를 고정해 둔다.
 */
class StoreControllerTest {

    private final StoreService storeService = mock(StoreService.class);

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new StoreController(storeService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    @DisplayName("파라미터 없는 검색은 첫 페이지 20건이다 — 기본값을 고정한다")
    void searchDefaultsToFirstPageOfTwenty() throws Exception {
        mockMvc.perform(get("/api/v1/storefront/products"))
                .andExpect(status().isOk());

        verify(storeService).search(null, 0, 20);
    }

    @Test
    @DisplayName("키워드와 페이지를 주면 그대로 넘어간다")
    void searchPassesGivenParameters() throws Exception {
        mockMvc.perform(get("/api/v1/storefront/products")
                        .param("q", "게임")
                        .param("page", "2")
                        .param("size", "50"))
                .andExpect(status().isOk());

        verify(storeService).search("게임", 2, 50);
    }

    @Test
    @DisplayName("[D-015] 페이지 번호가 숫자가 아니면 400 이다")
    void nonNumericPageIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/storefront/products").param("page", "first"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(storeService);
    }

    @Test
    @DisplayName("메인 진열은 파라미터 없이 열려 있다")
    void featuredNeedsNoParameters() throws Exception {
        mockMvc.perform(get("/api/v1/storefront/featured"))
                .andExpect(status().isOk());

        verify(storeService).featured();
    }

    /**
     * 위 테스트들은 서비스를 mock 으로 두므로 {@code PageRequest.of} 가 실행되지 않는다.
     * 범위를 벗어난 페이지 파라미터가 어떻게 응답되는지는 <b>실제 서비스를 태워야</b> 드러난다.
     */
    private final MockMvc realServiceMockMvc = MockMvcBuilders
            .standaloneSetup(new StoreController(
                    new StoreService(mock(ProductSearchRepository.class))))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    @DisplayName("[D-015 계열] 음수 페이지는 400 이어야 한다")
    void negativePageIsRejected() throws Exception {
        realServiceMockMvc.perform(get("/api/v1/storefront/products").param("page", "-1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("[D-015 계열] 크기 0 은 400 이어야 한다")
    void zeroSizeIsRejected() throws Exception {
        realServiceMockMvc.perform(get("/api/v1/storefront/products").param("size", "0"))
                .andExpect(status().isBadRequest());
    }
}
