package com.stove.order.core.domain;

/** Customer-facing amounts; provider costs and seller payouts are separate facts. */
public record OrderPrice(long listAmount, long discountAmount, long amount, String currency) {
    public OrderPrice {
        if (listAmount <= 0 || discountAmount < 0 || discountAmount >= listAmount
                || amount != listAmount - discountAmount || !"KRW".equals(currency))
            throw new IllegalArgumentException("주문 금액 구성이 올바르지 않습니다.");
    }
    public static OrderPrice atListPrice(PublishedProduct product) {
        return new OrderPrice(product.price(), 0, product.price(), product.currency());
    }
}
