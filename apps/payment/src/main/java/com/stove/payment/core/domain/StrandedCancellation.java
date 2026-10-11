package com.stove.payment.core.domain;

/**
 *  재개해야 할 취소 한 건. 엔티티가 아니라 값인 이유와 각 필드의.
 *
 * @param orderNo 주문·결제·이용권·정산을 연결하는 주문번호.
 * @param reason 처리 결과 또는 요청 사유에 대한 설명.
 * @param attempts 이미 수행한 환불 시도 횟수.
 */
public record StrandedCancellation(String orderNo, String reason, int attempts) {
}
