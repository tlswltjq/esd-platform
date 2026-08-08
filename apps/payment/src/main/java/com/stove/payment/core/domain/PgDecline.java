package com.stove.payment.core.domain;

/**
 * PG 승인 거절 사실. {@link PgApproval} 과 대칭이며, 어떤 경로(콜백 HTTP, 배치 대사)로
 * 들어왔는지와 무관하게 같은 형태로 다룬다.
 *
 * <p>승인과 달리 <b>금액도 멱등키도 없다.</b> 돈이 움직이지 않았으므로 PG 가 만들 승인 거래
 * 고유값이 없다. 중복 재전송은 {@code FAILED} 가 종단 상태라는 점으로 흡수한다
 * ({@link Payment#fail}).
 *
 * <p>{@code reasonCode} 는 PG 가 준 거절 코드를 그대로 싣는다 — 사람이 읽는 {@code reason}
 * 과 나눠 둬야 거절 사유별 집계가 가능하다.
 */
public record PgDecline(String orderNo, String pgTxId, String reasonCode, String reason) {
}
