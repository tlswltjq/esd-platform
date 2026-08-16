package com.stove.payment.core.domain;

/**
 * 재개해야 할 취소 한 건.
 *
 * <p>{@code CANCELING} 은 <b>돈이 나갔는지 불확실한 상태</b>다 — PG 호출 직전에 멈췄을 수도,
 * 호출은 갔는데 확정 커밋에서 멈췄을 수도 있다. 그 상태를 시간으로 끝내는 것이 재개이고,
 * 이 값이 그 대상이다.
 *
 * <p>엔티티가 아니라 값으로 넘기는 이유는 {@link PaymentCancellation} 과 같다 —
 * 실제 재개는 PG 호출을 끼고 돌아 트랜잭션 밖에서 일어나고,
 * 닫힌 트랜잭션의 엔티티를 만지면 지연 로딩 문제가 따라붙는다.
 *
 * @param reason 착수할 때 적힌 사유를 그대로 이어 쓴다. 새로 지어내면 이력이 끊긴다 —
 *               "사용자 환불" 로 시작한 건이 재개 뒤에 "시스템 재시도" 로 남으면 안 된다.
 */
public record StrandedCancellation(String orderNo, String reason) {
}
