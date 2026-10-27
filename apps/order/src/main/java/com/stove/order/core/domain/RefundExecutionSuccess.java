package com.stove.order.core.domain;

/** A late success enriches an attempt without replacing the first financial confirmation. */
public record RefundExecutionSuccess(RefundPaymentConfirmation execution, String confirmationEventId) {
    public RefundExecutionSuccess {
        if (execution == null || !"PG_RESPONSE".equals(execution.source()) || confirmationEventId == null
                || confirmationEventId.isBlank() || confirmationEventId.length() > 64)
            throw new RefundCompletionRejected("실행 성공과 원래 환불 완료의 연결 근거가 필요합니다.");
    }
}
