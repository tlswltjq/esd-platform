package com.stove.payment.core.port;

import com.stove.payment.core.domain.PgPreparation;

/**
 * 외부 PG 연동 포트. 우리가 <i>거는</i> 호출만 있다 —
 * 승인/거절은 웹훅으로 받으므로 {@code approve} 가 없다. docs/code-notes.md
 */
public interface PgClient {

    /** 결제 사전등록: 서버가 확정한 금액을 PG 에 먼저 등록하고 거래 ID를 받는다. */
    PgPreparation prepare(String orderNo, long amount, String currency, String method);

    /**
     * 승인 취소/환불. <b>{@code pgTxId} 기준 멱등이어야 한다</b> —
     * 이 계약이 깨지면 재시도가 곧 이중 환불이 된다. docs/code-notes.md
     */
    void cancel(String pgTxId, long amount, String reason);
}
