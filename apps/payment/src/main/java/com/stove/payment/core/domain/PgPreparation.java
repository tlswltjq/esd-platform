package com.stove.payment.core.domain;

/** PG 사전등록 결과. 어느 PG 사인지는 이 값에 드러나지 않는다. */
public record PgPreparation(String pgTxId, String redirectUrl) {
}
