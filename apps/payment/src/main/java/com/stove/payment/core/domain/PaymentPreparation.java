package com.stove.payment.core.domain;

/** PG 사전등록 결과. 클라이언트는 redirectUrl 로 이동해 승인 절차를 밟는다. */
public record PaymentPreparation(
        String orderNo,
        String pgTxId,
        long amount,
        String currency,
        String redirectUrl
) {
}
