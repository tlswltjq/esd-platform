package com.stove.order.core.domain;

/** catalog 에 가격 재계산을 요청할 항목. */
public record QuoteItem(Long productId, int quantity) {
}
