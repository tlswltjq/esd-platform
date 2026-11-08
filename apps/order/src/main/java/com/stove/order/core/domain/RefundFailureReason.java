package com.stove.order.core.domain;

import java.util.Optional;

/**
 * 결정 134: 고객에게 보이는 환불 실패 사유. 저장된 시도 실패 코드(RefundAttempt.failureReasonCode)에서 옮긴다.
 * 내부 코드와 PG 오류 원문(failureReason)은 고객 응답에 싣지 않는다.
 */
public enum RefundFailureReason {
    /** PG 취소가 실패했고 원결제가 취소되지 않았음을 확인했다(R7.3, 결정 128 실패 종결도 같은 RefundExecutionFailed로 온다). */
    PAYMENT_NOT_CANCELLED;

    /** 모르는 저장 코드는 추측해 옮기지 않는다. 새 내부 코드를 더하면 이 매핑도 함께 정한다. */
    public static Optional<RefundFailureReason> fromAttemptCode(String code) {
        return RefundAttempt.PG_CANCELLATION_FAILED.equals(code) ? Optional.of(PAYMENT_NOT_CANCELLED) : Optional.empty();
    }
}
