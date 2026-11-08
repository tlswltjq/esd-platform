package com.stove.payment.core.service;

import com.stove.common.kafka.contract.payload.RefundExecutionFailedEvent;
import com.stove.common.kafka.contract.payload.RefundPaymentConfirmedEvent;
import com.stove.common.messaging.outbox.OutboxRecorder;
import com.stove.payment.core.domain.Payment;
import com.stove.payment.core.domain.RefundCancellation;
import com.stove.payment.core.domain.RefundExecution;

/**
 * The one way a lookup-confirmed full cancellation is published, shared by the lookup worker and the operator closure, and
 * the one way a confirmed failure is published, shared by the lookup worker and the operator failure closure (decision 128).
 */
final class RefundConfirmationOutbox {
    private RefundConfirmationOutbox() { }

    static void recordUnattributed(OutboxRecorder outbox, Payment payment, RefundExecution execution) {
        outbox.record("Payment", execution.getOrderNo(), new RefundPaymentConfirmedEvent(execution.getRefundConfirmationEventId(),
                execution.getRefundConfirmedAt(), execution.getRefundId(), execution.getAttemptId(), execution.getRequestEventId(),
                execution.getOrderNo(), execution.getPaymentId(), execution.getBuyerSubject(), execution.getAmount(), execution.getCurrency(),
                "PG_QUERY_UNATTRIBUTED", payment.getProviderTransactionId(),
                execution.getConfirmedCancellations().stream().map(RefundCancellation::getCancellationId).toList(),
                CreateRefundAmountsSnapshot.from(payment, execution)));
    }

    static void recordFailure(OutboxRecorder outbox, Payment payment, RefundExecution execution, String cancellationId) {
        outbox.record("Payment", execution.getOrderNo(), new RefundExecutionFailedEvent(execution.getFailureEventId(),
                execution.getFailureConfirmedAt(), execution.getRefundId(), execution.getAttemptId(), execution.getRequestEventId(),
                execution.getOrderNo(), execution.getPaymentId(), execution.getBuyerSubject(), execution.getAmount(), execution.getCurrency(),
                payment.getProviderTransactionId(), cancellationId, execution.getFailureLookupHash()));
    }
}
