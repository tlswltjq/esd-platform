package com.stove.order.core.domain;

/**
 * 주문 상태. {@code CREATED} 이후는 결제 결과 이벤트로 바뀐다 —
 * <b>{@link #EXPIRED} 만 예외로 시간이 닫는다.</b> docs/code-notes.md
 */
public enum OrderStatus {
    /** 생성됨, 결제 대기 */
    CREATED,
    PAID,
    CANCELED,
    FAILED,
    /** 결제 창이 지나 만료됨. <b>{@code CANCELED} 와 합치면 안 된다</b> — docs/code-notes.md */
    EXPIRED
}
