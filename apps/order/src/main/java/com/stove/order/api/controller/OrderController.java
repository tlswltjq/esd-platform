package com.stove.order.api.controller;

import com.stove.common.security.CurrentSubject;
import com.stove.common.web.response.ApiResponse;
import com.stove.order.api.controller.dto.CreateOrderRequest;
import com.stove.order.api.controller.dto.OrderPage;
import com.stove.order.api.controller.dto.OrderResponse;
import com.stove.order.core.service.CreateOrder;
import com.stove.order.core.service.ReadOrder;
import com.stove.order.core.service.SearchOrders;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController @RequiredArgsConstructor
@SecurityRequirement(name = "oauth2", scopes = "commerce")
@RequestMapping("/api/v1/orders")
public class OrderController {
    private final CreateOrder createOrder;
    private final SearchOrders searchOrders;
    private final ReadOrder readOrder;

    @PostMapping
    public ApiResponse<OrderResponse> create(@CurrentSubject String buyer, @Valid @RequestBody CreateOrderRequest request) {
        return ApiResponse.ok(OrderResponse.from(createOrder.execute(buyer, request.toInput())));
    }
    @GetMapping
    public ApiResponse<OrderPage> list(@CurrentSubject String buyer, @RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(OrderPage.from(searchOrders.execute(buyer, page, size)));
    }
    @GetMapping("/{orderNo}")
    public ApiResponse<OrderResponse> detail(@CurrentSubject String buyer, @PathVariable String orderNo) {
        return ApiResponse.ok(OrderResponse.from(readOrder.execute(orderNo, buyer)));
    }
}
