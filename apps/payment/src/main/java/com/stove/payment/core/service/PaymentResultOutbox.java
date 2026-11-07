package com.stove.payment.core.service;

import com.stove.common.kafka.contract.payload.OrderPaymentCompletedEvent;
import com.stove.common.kafka.contract.payload.OrderPaymentFailedEvent;
import com.stove.common.messaging.outbox.OutboxRecorder;
import com.stove.payment.core.domain.Payment;
import com.stove.payment.core.domain.PaymentStatus;

/** Records the final purchase result for Order in the same transaction that committed it. The aggregate and key are the orderNo. */
final class PaymentResultOutbox {
    private PaymentResultOutbox() { }

    static void record(OutboxRecorder outbox, Payment current) {
        if (current.getStatus() == PaymentStatus.PAID) {
            outbox.record("Payment", current.getOrderNo(), new OrderPaymentCompletedEvent(
                    current.getResultEventId(), current.getResultOccurredAt(), current.getOrderNo(), current.getRequestEventId(),
                    current.getPaymentId(), current.getBuyerSubject(), current.getAmount(), current.getCurrency(),
                    current.getPgPaidAmount(), current.getPgDiscountAmount()));
        } else if (current.getStatus() == PaymentStatus.FAILED) {
            outbox.record("Payment", current.getOrderNo(), new OrderPaymentFailedEvent(
                    current.getResultEventId(), current.getResultOccurredAt(), current.getOrderNo(), current.getRequestEventId(),
                    current.getPaymentId(), current.getBuyerSubject(), current.getAmount(), current.getCurrency(),
                    current.getFailureReasonCode(), current.getFailureReason()));
        } else {
            throw new IllegalStateException("Only a final purchase result is published");
        }
    }
}
