package com.stove.payment.core.domain;

/**
 * PG 사전등록 결과. 어느 PG 사인지는 이 값에 드러나지 않는다.
 *
 * <p>{@code pgTxId} 는 이후 승인·취소 요청의 키가 되고, {@code redirectUrl} 은 사용자가
 * 승인 절차를 밟을 주소다.
 */
public record PgPreparation(String pgTxId, String redirectUrl) {
}
