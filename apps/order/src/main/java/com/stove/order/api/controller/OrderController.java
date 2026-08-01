package com.stove.order.api.controller;

import com.stove.common.core.response.ApiResponse;
import com.stove.order.api.application.PlaceOrderFacade;
import com.stove.order.api.controller.dto.CreateOrderRequest;
import com.stove.order.api.controller.dto.OrderResponse;
import com.stove.order.core.service.OrderCommandService;
import com.stove.order.core.service.OrderQueryService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * memberId 는 실제로는 게이트웨이가 검증한 토큰에서 주입된다.
 * 스켈레톤에서는 헤더/파라미터로 대체한다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/orders")
public class OrderController {

    private final PlaceOrderFacade placeOrderFacade;
    private final OrderQueryService orderQueryService;
    private final OrderCommandService orderCommandService;

    @PostMapping
    public ApiResponse<OrderResponse> create(@Valid @RequestBody CreateOrderRequest request) {
        return ApiResponse.ok(OrderResponse.from(placeOrderFacade.place(
                request.memberId(), request.toQuoteItems(), request.expectedAmount())));
    }

    @GetMapping("/{orderNo}")
    public ApiResponse<OrderResponse> get(@PathVariable String orderNo,
                                          @RequestHeader("X-Member-Id") Long memberId) {
        return ApiResponse.ok(OrderResponse.from(orderQueryService.getOrder(orderNo, memberId)));
    }

    @GetMapping
    public ApiResponse<List<OrderResponse>> myOrders(@RequestHeader("X-Member-Id") Long memberId) {
        return ApiResponse.ok(orderQueryService.getMyOrders(memberId).stream()
                .map(OrderResponse::from)
                .toList());
    }

    @PostMapping("/{orderNo}/cancel")
    public ApiResponse<Void> cancel(@PathVariable String orderNo,
                                    @RequestHeader("X-Member-Id") Long memberId,
                                    @RequestParam(defaultValue = "USER_CANCEL") String reason) {
        orderCommandService.cancelOrder(orderNo, memberId, reason);
        return ApiResponse.ok();
    }
}
