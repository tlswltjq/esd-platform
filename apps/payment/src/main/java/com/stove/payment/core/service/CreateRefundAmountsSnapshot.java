package com.stove.payment.core.service;

import com.stove.common.kafka.contract.payload.RefundAmountsSnapshot;
import com.stove.common.kafka.contract.payload.RefundCancellationAmount;
import com.stove.payment.core.domain.Payment;
import com.stove.payment.core.domain.RefundExecution;

/** Publishes verified stored facts; no payment-method refund amount is calculated. */
final class CreateRefundAmountsSnapshot {
    private CreateRefundAmountsSnapshot() { }

    static RefundAmountsSnapshot from(Payment payment, RefundExecution execution) {
        return new RefundAmountsSnapshot(payment.getPgPaidAmount(), payment.getPgDiscountAmount(),
                execution.getConfirmedCancellations().stream().map(cancellation -> new RefundCancellationAmount(
                        cancellation.getCancellationId(), cancellation.getAmount(), cancellation.getEasyPayDiscountAmount())).toList(),
                execution.getProviderRefundedAt());
    }
}
