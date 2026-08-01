package com.stove.order.api.controller.dto;

import com.stove.order.api.application.port.QuoteItem;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * 주문 생성 요청.
 * {@code expectedAmount} 는 <b>검증용</b>일 뿐이며 주문 금액으로 사용되지 않는다.
 * 서버가 catalog 에서 재계산한 금액과 다르면 409(PRICE_MISMATCH)로 거절한다.
 */
public record CreateOrderRequest(
        @NotNull Long memberId,
        @NotEmpty @Valid List<Item> items,
        Long expectedAmount
) {
    public record Item(@NotNull Long productId, @Min(1) int quantity) {
    }

    public List<QuoteItem> toQuoteItems() {
        return items.stream().map(item -> new QuoteItem(item.productId(), item.quantity())).toList();
    }
}
