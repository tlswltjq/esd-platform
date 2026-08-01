package com.stove.payment.core.domain;

/**
 * PG 승인 사실. 어떤 경로(콜백 HTTP, 배치 대사)로 들어왔는지와 무관하게 같은 형태로 다룬다.
 *
 * <p>{@code idempotencyKey} 는 PG 승인 거래의 고유값이며 DB 유니크 제약과 짝을 이뤄
 * 중복 승인을 차단한다.
 */
public record PgApproval(String orderNo, String pgTxId, long paidAmount, String idempotencyKey) {
}
