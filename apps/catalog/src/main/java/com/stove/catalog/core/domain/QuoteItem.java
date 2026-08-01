package com.stove.catalog.core.domain;

/** 가격 재계산 대상 한 줄. HTTP 요청 형식과 무관한 core 입력 모델이다. */
public record QuoteItem(Long productId, int quantity) {
}
