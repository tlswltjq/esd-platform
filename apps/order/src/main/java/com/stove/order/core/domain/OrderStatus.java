package com.stove.order.core.domain;

/** 주문 상태. 결제 결과 이벤트로만 CREATED 이후 상태가 바뀐다. */
public enum OrderStatus {
    /** 생성됨, 결제 대기 */
    CREATED,
    /** 결제 완료 */
    PAID,
    /** 사용자/시스템 취소 */
    CANCELED,
    /** 결제 실패로 종료 */
    FAILED
}
