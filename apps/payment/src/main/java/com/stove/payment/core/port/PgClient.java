package com.stove.payment.core.port;

import com.stove.payment.core.domain.PgPreparation;

/**
 * 외부 PG / 스토브캐시 연동 포트.
 * 도메인은 이 인터페이스에만 의존하므로 PG 사가 늘어나도 결제 규칙은 바뀌지 않는다.
 */
public interface PgClient {

    /** 결제 사전등록: 서버가 확정한 금액을 PG 에 먼저 등록하고 거래 ID를 받는다. */
    PgPreparation prepare(String orderNo, long amount, String currency, String method);

    /**
     * 승인 취소/환불.
     *
     * <p><b>{@code pgTxId} 기준 멱등이어야 한다.</b> 이미 취소된 거래에 다시 요청해도
     * 이중 환불이 되지 않아야 한다는 뜻이다. 취소는 "의도 기록 → PG 요청 → 확정" 순서로 진행되고
     * 중간에 멈춘 건은 재시도되므로, 이 성질이 없으면 재시도가 곧 이중 환불이 된다.
     */
    void cancel(String pgTxId, long amount, String reason);
}
