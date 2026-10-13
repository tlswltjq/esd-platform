package com.stove.order.api.controller;

import com.stove.common.web.response.ApiResponse;
import com.stove.common.security.CurrentMemberId;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@SecurityRequirement(name = "oauth2", scopes = "commerce")
@RequiredArgsConstructor
@RequestMapping("/api/v1/orders")
public class OrderController {

    private final PlaceOrderFacade placeOrderFacade;
    private final OrderQueryService orderQueryService;
    private final OrderCommandService orderCommandService;

    @PostMapping
    public ApiResponse<OrderResponse> create(@CurrentMemberId Long memberId,
                                              @Valid @RequestBody CreateOrderRequest request) {
        return ApiResponse.ok(OrderResponse.from(placeOrderFacade.place(
                memberId, request.toQuoteItems(), request.expectedAmount())));
    }

    @GetMapping("/{orderNo}")
    public ApiResponse<OrderResponse> get(@PathVariable String orderNo,
                                          @CurrentMemberId Long memberId) {
        return ApiResponse.ok(OrderResponse.from(orderQueryService.getOrder(
                orderNo, memberId)));
    }

    @GetMapping
    public ApiResponse<List<OrderResponse>> myOrders(@CurrentMemberId Long memberId) {
        return ApiResponse.ok(orderQueryService.getMyOrders(memberId).stream()
                .map(OrderResponse::from)
                .toList());
    }

    @PostMapping("/{orderNo}/cancel")
    public ApiResponse<Void> cancel(@PathVariable String orderNo,
                                    @CurrentMemberId Long memberId,
                                    @RequestParam(defaultValue = "USER_CANCEL") String reason) {
        orderCommandService.cancelOrder(orderNo, memberId, reason);
        return ApiResponse.ok();
    }
}
