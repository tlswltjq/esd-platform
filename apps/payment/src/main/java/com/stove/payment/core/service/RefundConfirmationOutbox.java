package com.stove.payment.core.service;

import com.stove.common.kafka.contract.payload.RefundExecutionFailedEvent;
import com.stove.common.kafka.contract.payload.RefundFailureClosedEvent;
import com.stove.common.kafka.contract.payload.RefundPaymentConfirmedEvent;
import com.stove.common.messaging.outbox.OutboxRecorder;
import com.stove.payment.core.domain.Payment;
import com.stove.payment.core.domain.RefundCancellation;
import com.stove.payment.core.domain.RefundExecution;
import com.stove.payment.core.domain.RefundFailureClosure;

/**
 * The one way a lookup-confirmed full cancellation is published, shared by the lookup worker and the operator closure. A
 * PG-confirmed failure and an operator failure closure (decision 128) are different facts and have their own events
 * (decision 148): the closure never travels as {@code RefundExecutionFailed}.
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

    /** The PG confirmed our own cancellation FAILED and the original payment uncancelled; the cancellation ID is that PG ID. */
    static void recordFailure(OutboxRecorder outbox, Payment payment, RefundExecution execution) {
        outbox.record("Payment", execution.getOrderNo(), new RefundExecutionFailedEvent(execution.getFailureEventId(),
                execution.getFailureConfirmedAt(), execution.getRefundId(), execution.getAttemptId(), execution.getRequestEventId(),
                execution.getOrderNo(), execution.getPaymentId(), execution.getBuyerSubject(), execution.getAmount(), execution.getCurrency(),
                payment.getProviderTransactionId(), execution.getReceiptCancellationId(), execution.getFailureLookupHash()));
    }

    /** An operator closed the attempt as failed on a PG re-inspection (decision 128), linked to a failed PG cancellation or not. */
    static void recordFailureClosure(OutboxRecorder outbox, Payment payment, RefundExecution execution, RefundFailureClosure closure) {
        outbox.record("Payment", execution.getOrderNo(), new RefundFailureClosedEvent(execution.getFailureEventId(),
                closure.getClosedAt(), execution.getRefundId(), execution.getAttemptId(), execution.getRequestEventId(),
                execution.getOrderNo(), execution.getPaymentId(), execution.getBuyerSubject(), execution.getAmount(), execution.getCurrency(),
                payment.getProviderTransactionId(), closure.getRequestId(), RefundFailureClosure.OBSERVED_PAYMENT_STATUS,
                closure.getEvidenceObservedAt(), closure.getSnapshotHash(), closure.getFailedCancellationId()));
    }
}
