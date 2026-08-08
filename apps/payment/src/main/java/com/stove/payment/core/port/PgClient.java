package com.stove.payment.core.port;

import com.stove.payment.core.domain.PgPreparation;

/**
 * 외부 PG / 스토브캐시 연동 포트.
 * 도메인은 이 인터페이스에만 의존하므로 PG 사가 늘어나도 결제 규칙은 바뀌지 않는다.
 *
 * <p><b>승인 결과는 이 포트로 오지 않는다.</b> 여기 있는 것은 우리가 <i>거는</i> 호출
 * (사전등록·취소)뿐이고, 승인/거절은 PG 가 우리 콜백 엔드포인트로 밀어주는 <b>웹훅 모델</b>로
 * 받는다({@code POST /api/v1/payments/callback}). 그래서 {@code approve} 가 없다.
 *
 * <p>실 연동에서는 가맹점이 승인 API 를 직접 호출하는 동기 경로가 주가 되는 경우가 많다 —
 * 그때는 이 포트에 {@code approve} 가 추가되고 거절은 그 <b>반환값</b>으로 온다.
 * 진입 경로가 바뀌어도 {@link com.stove.payment.core.domain.Payment#fail} 의 상태 가드는
 * 그대로 쓰인다. 호출 지점만 컨트롤러에서 서비스 안쪽으로 옮겨간다.
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
