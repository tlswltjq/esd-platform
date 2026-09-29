package com.stove.order.api.controller;

import com.stove.common.core.response.ApiResponse;
import com.stove.common.security.CommerceIdentity;
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
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
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
    public ApiResponse<OrderResponse> create(@AuthenticationPrincipal Jwt jwt,
                                              @Valid @RequestBody CreateOrderRequest request) {
        return ApiResponse.ok(OrderResponse.from(placeOrderFacade.place(
                CommerceIdentity.memberId(jwt), request.toQuoteItems(), request.expectedAmount())));
    }

    @GetMapping("/{orderNo}")
    public ApiResponse<OrderResponse> get(@PathVariable String orderNo,
                                          @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(OrderResponse.from(orderQueryService.getOrder(
                orderNo, CommerceIdentity.memberId(jwt))));
    }

    @GetMapping
    public ApiResponse<List<OrderResponse>> myOrders(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(orderQueryService.getMyOrders(CommerceIdentity.memberId(jwt)).stream()
                .map(OrderResponse::from)
                .toList());
    }

    @PostMapping("/{orderNo}/cancel")
    public ApiResponse<Void> cancel(@PathVariable String orderNo,
                                    @AuthenticationPrincipal Jwt jwt,
                                    @RequestParam(defaultValue = "USER_CANCEL") String reason) {
        orderCommandService.cancelOrder(orderNo, CommerceIdentity.memberId(jwt), reason);
        return ApiResponse.ok();
    }
}
