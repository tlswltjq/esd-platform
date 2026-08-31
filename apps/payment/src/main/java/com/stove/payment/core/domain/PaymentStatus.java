package com.stove.payment.core.domain;

public enum PaymentStatus {
    /** 주문 생성 이벤트 수신, 결제 대기 */
    READY,
    /** PG 사전등록 완료(결제창 호출 가능) */
    PENDING,
    PAID,
    /**
     * 취소 착수 — PG 환불을 요청하기로 커밋한 상태.
     *
     * <p>PG 환불은 되돌릴 수 없으므로 트랜잭션 안에서 부를 수 없다. 그렇다고 커밋 후에 부르면
     * "장부는 취소인데 돈은 안 나감"이 조용히 생긴다. 그래서 의도를 먼저 기록하고 나간다 —
     * 여기서 멈춘 건은 돈이 나갔는지 불확실하다는 뜻이며, 재시도 대상으로 눈에 띈다.
     */
    CANCELING,
    CANCELED,
    FAILED
}
