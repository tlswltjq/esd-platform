package com.stove.payment.infrastructure.pg;

/**
 * 외부 PG / 스토브캐시 연동 포트.
 * 도메인은 이 인터페이스에만 의존하므로 PG 사가 늘어나도 결제 규칙은 바뀌지 않는다.
 */
public interface PgClient {

    /** 결제 사전등록: 서버가 확정한 금액을 PG 에 먼저 등록하고 거래 ID를 받는다. */
    PgPrepareResult prepare(String orderNo, long amount, String currency, String method);

    /** 승인 취소/환불 */
    void cancel(String pgTxId, long amount, String reason);

    record PgPrepareResult(String pgTxId, String redirectUrl) {
    }
}
