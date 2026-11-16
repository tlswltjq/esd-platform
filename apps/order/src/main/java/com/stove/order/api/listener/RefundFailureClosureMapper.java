package com.stove.order.api.listener;

import com.stove.common.kafka.EventEnvelope;
import com.stove.common.kafka.contract.payload.RefundFailureClosedEvent;
import com.stove.order.core.domain.RefundCompletionRejected;
import com.stove.order.core.domain.RefundFailureConfirmation;
import java.util.Objects;

/**
 * 결정 148: Payment 운영자 실패 종결({@code RefundFailureClosed})을 실패 확정 입력으로 바꾼다. Order는 PG 확정 실패와 같은 업무
 * 반응을 하므로 두 사실은 여기서 한 내부 입력으로 합치고, 출처와 종결 ID를 입력에 남긴다.
 */
public final class RefundFailureClosureMapper {
    /** 종결은 PG 재조회에서 원결제가 PAID이고 취소액이 0일 때만 일어난다. 다른 상태는 계약 위반이다. */
    static final String OBSERVED_PAYMENT_STATUS = "PAID";

    private RefundFailureClosureMapper() { }

    public static RefundFailureConfirmation from(EventEnvelope envelope, RefundFailureClosedEvent event) {
        if (envelope == null || event == null || !Objects.equals(envelope.eventId(), event.eventId())
                || !Objects.equals(envelope.eventType(), event.eventType()) || !Objects.equals(envelope.key(), event.orderNo()))
            throw new RefundCompletionRejected("실패 종결 봉투·본문·키가 다릅니다.");
        if (!OBSERVED_PAYMENT_STATUS.equals(event.observedPaymentStatus()))
            throw new RefundCompletionRejected("실패 종결의 PG 관측 상태가 미취소 결제가 아닙니다.");
        return RefundFailureConfirmation.operatorClosed(event.eventId(), event.occurredAt(), event.refundId(), event.attemptId(),
                event.requestEventId(), event.orderNo(), event.paymentId(), event.buyerSubject(), event.amount(), event.currency(),
                event.providerTransactionId(), event.failedCancellationId(), event.lookupHash(), event.closureId(), event.evidenceObservedAt());
    }
}
