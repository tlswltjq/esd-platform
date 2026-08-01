package com.stove.catalog.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/** 주문 생성 시 order 가 호출하는 서버 측 가격 재계산 요청 */
public record QuoteRequest(@NotEmpty @Valid List<Item> items) {

    public record Item(@NotNull Long productId, @Min(1) int quantity) {
    }
}
