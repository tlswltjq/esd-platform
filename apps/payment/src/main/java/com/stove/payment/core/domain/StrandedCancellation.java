package com.stove.payment.core.domain;

/** 재개해야 할 취소 한 건. 엔티티가 아니라 값인 이유와 각 필드의 근거는 docs/code-notes.md */
public record StrandedCancellation(String orderNo, String reason, int attempts) {
}
