package com.stove.order.api.controller.dto;

import com.stove.order.core.domain.*;
import java.time.Instant;

public record OrderResponse(String orderNo, Long productId, String productCode, String title,
        long contentVersion, long listAmount, long discountAmount, long amount, String currency,
        OrderStatus status, Instant createdAt, Instant updatedAt, Instant resultOccurredAt,
        String failureReasonCode, String failureReason) {
    public static OrderResponse from(Order o) {
        return new OrderResponse(o.getOrderNo(), o.getProductId(), o.getProductCode(), o.getTitle(),
                o.getContentVersion(), o.getListAmount(), o.getDiscountAmount(), o.getAmount(), o.getCurrency(),
                o.getStatus(), o.getCreatedAt(), o.getUpdatedAt(), o.getResultOccurredAt(),
                o.getFailureReasonCode(), o.getFailureReason());
    }
}
