package com.stove.payment.core.domain;

/**
 *  PG 승인 사실. 진입 경로와 무관하게 같은 형태로 다룬다.
 *
 * @param orderNo 주문·결제·이용권·정산을 연결하는 주문번호.
 * @param pgTxId PG에 사전등록한 거래 식별자.
 * @param paidAmount 고객이 실제로 결제한 금액.
 * @param idempotencyKey 같은 요청의 중복 처리를 방지하는 키.
 */
public record PgApproval(String orderNo, String pgTxId, long paidAmount, String idempotencyKey) {
}
