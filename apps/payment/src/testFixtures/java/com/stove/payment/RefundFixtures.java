package com.stove.payment;

import static com.stove.payment.PaymentFixtures.approved;
import static com.stove.payment.PaymentFixtures.id;
import static com.stove.payment.PaymentFixtures.request;

import com.stove.common.kafka.contract.payload.RefundExecutionRequestedEvent;
import com.stove.payment.core.domain.Payment;
import com.stove.payment.core.domain.RefundExecutionRequest;
import java.time.Instant;

public final class RefundFixtures {
    private RefundFixtures() {}

    public static Payment paid() {
        Payment paid = Payment.receive(request(), "SIMULATED");
        paid.confirm(approved(paid), id(), Payment.now());
        return paid;
    }

    public static RefundExecutionRequest execution(Payment paid) {
        return new RefundExecutionRequest(id(), Instant.parse("2026-10-27T01:00:00.123456Z"), id(), id(), paid.getOrderNo(),
                paid.getPaymentId(), paid.getBuyerSubject(), paid.getAmount(), paid.getCurrency(), id());
    }

    public static RefundExecutionRequestedEvent event(RefundExecutionRequest input) {
        return new RefundExecutionRequestedEvent(input.eventId(), input.occurredAt(), input.refundId(), input.attemptId(),
                input.orderNo(), input.paymentId(), input.buyerSubject(), input.amount(), input.currency(), input.licenseSuspensionEventId());
    }
}
