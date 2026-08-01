package com.stove.catalog.api.controller.dto;

import com.stove.catalog.core.domain.QuoteItem;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/** 주문 생성 시 order 가 호출하는 서버 측 가격 재계산 요청 */
public record QuoteRequest(@NotEmpty @Valid List<Item> items) {

    public record Item(@NotNull Long productId, @Min(1) int quantity) {
    }

    /** HTTP 요청 형식을 core 입력 모델로 옮긴다. 형식 검증은 이 시점에 이미 끝나 있다. */
    public List<QuoteItem> toItems() {
        return items.stream().map(item -> new QuoteItem(item.productId(), item.quantity())).toList();
    }
}
