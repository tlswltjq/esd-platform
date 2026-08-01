package com.stove.payment.domain;

public enum PaymentStatus {
    /** 주문 생성 이벤트 수신, 결제 대기 */
    READY,
    /** PG 사전등록 완료(결제창 호출 가능) */
    PENDING,
    /** 승인 완료 */
    PAID,
    /** 취소/환불 완료 */
    CANCELED,
    /** 승인 실패 */
    FAILED
}
