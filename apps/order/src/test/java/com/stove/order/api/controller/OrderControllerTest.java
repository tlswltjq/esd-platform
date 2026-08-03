package com.stove.order.api.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.web.GlobalExceptionHandler;
import com.stove.order.api.controller.dto.CreateOrderRequest;
import com.stove.order.api.application.PlaceOrderFacade;
import com.stove.order.core.service.OrderCommandService;
import com.stove.order.core.service.OrderQueryService;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 주문 생성의 입구 검증.
 *
 * <p>주문은 결제 · 라이선스 · 정산으로 이어지는 흐름의 출발점이라
 * 여기서 통과한 값이 시스템 전체를 돈다. 수량 하나가 음수로 새어 들어가면
 * catalog 재계산(D-009) · 정산 원장까지 함께 틀어진다.
 *
 * <p>스프링 컨텍스트를 띄우지 않는 이유는 {@code ProductControllerTest} 와 같다 —
 * 앱 클래스의 {@code @EnableJpaRepositories} 가 DB 를 요구하는데,
 * 여기서 볼 것은 디스패처가 컨트롤러에 닿기 전에 무엇을 걸러내는가뿐이다.
 */
class OrderControllerTest {

    private final ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();
    private final PlaceOrderFacade placeOrderFacade = mock(PlaceOrderFacade.class);
    private final OrderQueryService orderQueryService = mock(OrderQueryService.class);
    private final OrderCommandService orderCommandService = mock(OrderCommandService.class);

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new OrderController(placeOrderFacade, orderQueryService, orderCommandService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    private String body(Long memberId, Long productId, int quantity) throws Exception {
        return objectMapper.writeValueAsString(new CreateOrderRequest(
                memberId, List.of(new CreateOrderRequest.Item(productId, quantity)), 30_000L));
    }

    @Test
    @DisplayName("음수 수량 주문은 400 으로 막힌다")
    void negativeQuantityIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(42L, 1L, -1)))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(placeOrderFacade);
    }

    @Test
    @DisplayName("수량 0 주문도 400 이다")
    void zeroQuantityIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(42L, 1L, 0)))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(placeOrderFacade);
    }

    @Test
    @DisplayName("회원 없는 주문은 400 이다")
    void missingMemberIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(null, 1L, 1)))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(placeOrderFacade);
    }

    @Test
    @DisplayName("품목 없는 주문은 400 이다")
    void emptyItemsIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateOrderRequest(42L, List.of(), 0L))))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(placeOrderFacade);
    }
}
