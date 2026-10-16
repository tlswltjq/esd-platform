package com.stove.order.api.controller.dto;

import com.stove.order.core.domain.OrderRequest;
import jakarta.validation.constraints.*;

public record CreateOrderRequest(@NotNull @Pattern(regexp = OrderRequest.UUID_PATTERN) String requestId,
        @NotNull @Pattern(regexp = OrderRequest.PRODUCT_CODE_PATTERN) String productCode,
        @NotNull @Positive Long expectedContentVersion, @NotNull @PositiveOrZero Long expectedAmount) {
    public OrderRequest toInput() { return new OrderRequest(requestId, productCode, expectedContentVersion, expectedAmount); }
}
