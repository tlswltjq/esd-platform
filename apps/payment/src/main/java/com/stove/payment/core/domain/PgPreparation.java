package com.stove.payment.core.domain;

/**
 *  PG 사전등록 결과. 어느 PG 사인지는 이 값에 드러나지 않는다.
 *
 * @param pgTxId PG에 사전등록한 거래 식별자.
 * @param redirectUrl 사용자가 결제를 진행할 결제창 URL.
 */
public record PgPreparation(String pgTxId, String redirectUrl) {
}
