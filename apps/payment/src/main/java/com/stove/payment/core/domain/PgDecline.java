package com.stove.payment.core.domain;

/**
 * PG 승인 거절 사실. 승인과 달리 <b>금액도 멱등키도 없다</b> — 돈이 움직이지 않았다.
 *
 *
 * @param orderNo 주문·결제·이용권·정산을 연결하는 주문번호.
 * @param pgTxId PG에 사전등록한 거래 식별자.
 * @param reasonCode 처리 결과 또는 요청 사유를 구분하는 코드.
 * @param reason 처리 결과 또는 요청 사유에 대한 설명.
 */
public record PgDecline(String orderNo, String pgTxId, String reasonCode, String reason) {
}
