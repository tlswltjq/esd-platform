package com.stove.catalog.api.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.catalog.api.controller.dto.QuoteRequest;
import com.stove.catalog.api.application.ProductReindexFacade;
import com.stove.catalog.core.service.ProductCommandService;
import com.stove.catalog.core.domain.Quote;
import com.stove.catalog.core.service.ProductQueryService;
import com.stove.common.event.payload.OrderLine;
import com.stove.common.web.GlobalExceptionHandler;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * HTTP 검증과 도메인 검증의 <b>경계</b>.
 *
 * <p>D-009 는 서버 측 금액 재계산에 수량 검증이 없어 음수 수량으로 총액을 깎을 수 있었던 결함이다.
 * 수정으로 방어가 두 겹이 됐다 — DTO 의 {@code @Min(1)} 과 {@code QuoteItem} 의 도메인 검증.
 *
 * <p>여기서 확인하는 것은 <b>바깥 겹이 실제로 동작하는가</b>다.
 * 안쪽 겹은 {@code ProductQuoteTest} 가 이미 본다. 두 겹이 있다는 사실만으로 부족한 이유는,
 * 바깥 겹이 조용히 빠져도(어노테이션 하나 지우면 그만이다) 안쪽이 막아 주므로
 * <b>테스트가 통과하고 아무도 모른다</b>는 데 있다. 그러면 방어는 다시 한 겹이 되고,
 * 다음 수정에서 안쪽이 무너지면 그대로 뚫린다.
 *
 * <p>스프링 컨텍스트를 띄우지 않는다. {@code @WebMvcTest} 는 앱 클래스의
 * {@code @EnableJpaRepositories} 까지 끌고 와 DB 를 요구하는데,
 * 여기서 볼 것은 <b>디스패처가 컨트롤러에 닿기 전에 무엇을 걸러내는가</b>뿐이라
 * standalone 구성으로 충분하다.
 */
class ProductControllerTest {

    private final ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();
    private final ProductQueryService productQueryService = mock(ProductQueryService.class);
    private final ProductCommandService productCommandService = mock(ProductCommandService.class);
    private final ProductReindexFacade productReindexFacade = mock(ProductReindexFacade.class);

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new ProductController(
                    productQueryService, productCommandService, productReindexFacade))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    private String body(Long productId, int quantity) throws Exception {
        return objectMapper.writeValueAsString(
                new QuoteRequest(List.of(new QuoteRequest.Item(productId, quantity))));
    }

    @Test
    @DisplayName("[D-009] 음수 수량은 서비스에 닿기 전에 400 으로 막힌다")
    void negativeQuantityIsRejectedAtTheEdge() throws Exception {
        mockMvc.perform(post("/api/v1/products/quote")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(1L, -1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));

        // 핵심은 이 단언이다 — 도메인까지 내려가지 않고 입구에서 끝난다.
        verify(productQueryService, never()).quote(any());
    }

    @Test
    @DisplayName("[D-009] 수량 0 도 마찬가지다")
    void zeroQuantityIsRejectedAtTheEdge() throws Exception {
        mockMvc.perform(post("/api/v1/products/quote")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(1L, 0)))
                .andExpect(status().isBadRequest());

        verify(productQueryService, never()).quote(any());
    }

    @Test
    @DisplayName("productId 가 없으면 400 이다")
    void missingProductIdIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/products/quote")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(null, 1)))
                .andExpect(status().isBadRequest());

        verify(productQueryService, never()).quote(any());
    }

    @Test
    @DisplayName("빈 주문은 400 이다 — 0원짜리 주문이 성립하면 안 된다")
    void emptyItemsIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/products/quote")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new QuoteRequest(List.of()))))
                .andExpect(status().isBadRequest());

        verify(productQueryService, never()).quote(any());
    }

    @Test
    @DisplayName("정상 수량은 서비스로 넘어가고 서버가 확정한 금액이 응답된다")
    void validQuantityReachesTheService() throws Exception {
        when(productQueryService.quote(any())).thenReturn(new Quote(
                List.of(new OrderLine(1L, "게임 A", 1001L, 30_000L, 2)), 60_000L, "KRW"));

        mockMvc.perform(post("/api/v1/products/quote")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(1L, 2)))
                .andExpect(status().isOk())
                // 응답 금액은 클라이언트가 보낸 값이 아니라 서버가 재계산한 값이다.
                .andExpect(jsonPath("$.data.totalAmount").value(60_000));

        verify(productQueryService).quote(any());
    }
}
