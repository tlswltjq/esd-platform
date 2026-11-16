package com.stove.order.core.domain;

/**
 * 결정 148: 환불 시도 실패를 확정한 근거의 출처. 두 사실은 Payment에서 서로 다른 이벤트로 오고, Order는 같은 업무 반응(보유권 복구 요청·
 * 고객 재시도 허용)을 하되 어느 사실로 실패했는지를 시도에 남긴다. 저장 코드는 {@link RefundAttempt#getFailureReasonCode()}다.
 */
public enum RefundFailureSource {
    /** Payment가 우리 PG 취소의 FAILED와 원결제 미취소를 확인했다({@code RefundExecutionFailed}, R7.3). */
    PG_CANCELLATION_FAILED,
    /** 운영자가 PG 재조회(원결제 PAID·취소액 0)를 근거로 자동 송신이 멈춘 시도를 실패로 종결했다({@code RefundFailureClosed}, 결정 128). */
    OPERATOR_FAILURE_CLOSED
}
