package com.stove.payment.core.domain;

/**
 * 주문 대금의 승인 및 환불 진행 상태.
 */
public enum PaymentStatus {
    /** 주문 생성 이벤트 수신, 결제 대기 */
    READY,
    /** PG 사전등록 완료(결제창 호출 가능) */
    PENDING,
    /** 결제 승인이 확정된 상태. */
    PAID,
    /** 취소 착수 — <b>돈이 나갔는지 불확실한 상태.</b> */
    CANCELING,
    /** PG 환불 완료를 확인한 상태. */
    CANCELED,
    /** PG 승인 거절로 결제가 실패한 상태. */
    FAILED
}
