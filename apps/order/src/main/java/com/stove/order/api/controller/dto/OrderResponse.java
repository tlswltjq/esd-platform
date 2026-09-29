package com.stove.order.api.controller.dto;

import com.stove.order.core.domain.Order;
import com.stove.order.core.domain.OrderItem;
import com.stove.order.core.domain.OrderStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record OrderResponse(
        String orderNo,
        Long memberId,
        OrderStatus status,
        long totalAmount,
        String currency,
        List<Line> lines,
        Instant paidAt,
        boolean retryable
) {
    public record Line(Long productId, String productName, Long sellerId, long unitPrice, int quantity,
                       long listUnitPrice, long discountPerUnit, Long promotionId, String discountBearer,
                       Long settlementBasis, BigDecimal feeRate, Long feeAmount, Long sellerPayout) {
        static Line from(OrderItem item) {
            return new Line(item.getProductId(), item.getProductName(), item.getSellerId(),
                    item.getUnitPrice(), item.getQuantity(), item.getListUnitPrice(),
                    item.getDiscountPerUnit(), item.getPromotionId(), item.getDiscountBearer(),
                    item.getSettlementBasis(), item.getFeeRate(), item.getFeeAmount(), item.getSellerPayout());
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
                order.getPaidAt(),
                order.getStatus() == OrderStatus.CREATED);
    }
}
