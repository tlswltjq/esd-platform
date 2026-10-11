package com.stove.payment.core.domain;

/**
 *  PG 사전등록 결과. 클라이언트는 redirectUrl 로 이동해 승인 절차를 밟는다.
 *
 * @param orderNo 주문·결제·이용권·정산을 연결하는 주문번호.
 * @param pgTxId PG에 사전등록한 거래 식별자.
 * @param amount 결제 또는 환불 대상 금액(최소 화폐 단위).
 * @param currency 금액의 통화 코드.
 * @param redirectUrl 사용자가 결제를 진행할 결제창 URL.
 */
public record PaymentPreparation(
        String orderNo,
        String pgTxId,
        long amount,
        String currency,
        String redirectUrl
) {
}
