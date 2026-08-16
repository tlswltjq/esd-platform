package com.stove.order.core.domain;

/**
 * 주문 상태.
 *
 * <p>{@code CREATED} 이후 상태는 결제 결과 이벤트로 바뀐다 — <b>{@link #EXPIRED} 만 예외다.</b>
 * 결제를 시작조차 하지 않은 주문은 아무 이벤트도 낳지 않아서, 이벤트로만 상태가 바뀌는 규칙
 * 아래에서는 영원히 {@code CREATED} 로 남는다. 그 자리를 시간이 대신 닫는다.
 */
public enum OrderStatus {
    /** 생성됨, 결제 대기 */
    CREATED,
    /** 결제 완료 */
    PAID,
    /** 사용자/시스템 취소 */
    CANCELED,
    /** 결제 실패로 종료 */
    FAILED,
    /**
     * 결제 창이 지나 만료됨.
     *
     * <p>{@code CANCELED} 와 나눠 두는 이유는 <b>둘이 다른 일을 뜻하기 때문</b>이다.
     * {@code CANCELED} 는 누군가 되돌린 것이고 되돌릴 돈이 있었다. {@code EXPIRED} 는
     * <b>아무 일도 일어나지 않은 채 시간이 지난 것</b>이라 되돌릴 것이 없다.
     * 합치면 "취소된 주문 수" 지표가 장바구니 방치까지 세게 되고, 그러면 그 지표를 못 쓴다.
     */
    EXPIRED
}
