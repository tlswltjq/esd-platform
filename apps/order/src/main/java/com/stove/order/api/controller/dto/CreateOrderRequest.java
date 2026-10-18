package com.stove.order.api.controller.dto;

import com.stove.order.core.domain.OrderRequest;
import com.stove.order.core.domain.OrderValues;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

public record CreateOrderRequest(@NotNull @Pattern(regexp = OrderValues.UUID_PATTERN) String requestId,
        @NotNull @Pattern(regexp = OrderValues.PRODUCT_CODE_PATTERN) String productCode,
        @NotNull @Positive Long expectedContentVersion, @NotNull @PositiveOrZero Long expectedAmount) {
    public OrderRequest toInput() { return new OrderRequest(requestId, productCode, expectedContentVersion, expectedAmount); }
}
