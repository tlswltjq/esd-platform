package com.stove.payment.core.domain;

/**
 * 취소 1단계의 결과. "PG 에 환불을 요청해야 하는가"와 그때 필요한 값만 담는다.
 *
 * <p>서비스가 엔티티 대신 이 값을 돌려주는 이유는, PG 호출이 트랜잭션 밖에서 일어나야 하기 때문이다.
 * 트랜잭션이 닫힌 뒤 엔티티를 만지면 지연 로딩·영속성 컨텍스트 문제가 따라붙는다.
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
