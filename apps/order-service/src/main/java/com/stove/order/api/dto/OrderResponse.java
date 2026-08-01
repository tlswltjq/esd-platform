package com.stove.order.api.dto;

import com.stove.order.domain.Order;
import com.stove.order.domain.OrderItem;
import com.stove.order.domain.OrderStatus;
import java.time.Instant;
import java.util.List;

public record OrderResponse(
        String orderNo,
        Long memberId,
        OrderStatus status,
        long totalAmount,
        String currency,
        List<Line> lines,
        Instant paidAt
) {
    public record Line(Long productId, String productName, long unitPrice, int quantity) {
        static Line from(OrderItem item) {
            return new Line(item.getProductId(), item.getProductName(), item.getUnitPrice(), item.getQuantity());
        }
    }

    public static OrderResponse from(Order order) {
        return new OrderResponse(
                order.getOrderNo(),
                order.getMemberId(),
                order.getStatus(),
                order.getTotalAmount(),
                order.getCurrency(),
                order.getItems().stream().map(Line::from).toList(),
                order.getPaidAt());
    }
}
