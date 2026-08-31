package com.stove.payment.core.domain;

/** PG 승인 사실. 진입 경로와 무관하게 같은 형태로 다룬다. */
public record PgApproval(String orderNo, String pgTxId, long paidAmount, String idempotencyKey) {
}
