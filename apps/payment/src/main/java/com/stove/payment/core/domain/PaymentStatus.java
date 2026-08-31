package com.stove.payment.core.domain;

public enum PaymentStatus {
    /** 주문 생성 이벤트 수신, 결제 대기 */
    READY,
    /** PG 사전등록 완료(결제창 호출 가능) */
    PENDING,
    PAID,
    /** 취소 착수 — <b>돈이 나갔는지 불확실한 상태.</b> docs/code-notes.md */
    CANCELING,
    CANCELED,
    FAILED
}
