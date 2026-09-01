package com.stove.payment.core.domain;

/**
 * 취소 1단계의 결과. 엔티티가 아니라 값인 이유는 docs/code-notes.md
 *
 * @param pgRefundRequired PG 환불 요청이 필요한가. 이미 취소된 건이거나 중복 이벤트면 false
 */
public record PaymentCancellation(boolean pgRefundRequired, String pgTxId, long amount) {

    private static final PaymentCancellation NONE = new PaymentCancellation(false, null, 0L);

    /** 할 일이 없다 — 이미 취소됐거나, 중복 이벤트거나, 상태가 어긋나 보상을 건너뛴 경우 */
    public static PaymentCancellation none() {
        return NONE;
    }

    public static PaymentCancellation of(String pgTxId, long amount) {
        return new PaymentCancellation(true, pgTxId, amount);
    }
}
