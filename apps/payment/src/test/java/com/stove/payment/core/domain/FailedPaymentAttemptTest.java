package com.stove.payment.core.domain;

import static com.stove.payment.PaymentFixtures.id;
import static com.stove.payment.PaymentFixtures.request;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class FailedPaymentAttemptTest {
    private final Payment payment = Payment.receive(request(), "PORTONE_TEST");
    private final Instant requested = Payment.now().plusSeconds(1);
    private final PgFailedPaymentAttempt proof = new PgFailedPaymentAttempt(payment.chargeRequest(), "supplier-attempt-1", requested, requested.plusSeconds(1));

    @Test void aVerifiedAttemptFailurePreservesThePendingPaymentAndOriginalCheckoutPurchase() {
        PaymentOrderRequest original = payment.orderRequest();
        Instant due = payment.getNextAttemptAt();
        FailedPaymentAttempt failure = FailedPaymentAttempt.record(payment, proof, requested.plusSeconds(2));
        assertThat(failure.getPaymentId()).isEqualTo(payment.getPaymentId());
        assertThat(failure.getSupplierAttemptId()).isEqualTo(proof.supplierAttemptId());
        assertThat(failure.getRequestedAt()).isEqualTo(requested);
        assertThat(failure.getFailedAt()).isEqualTo(requested.plusSeconds(1));
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(payment.getResultEventId()).isNull(); assertThat(payment.getFailureReasonCode()).isNull();
        assertThat(payment.getNextAttemptAt()).isEqualTo(due); assertThat(payment.orderRequest()).isEqualTo(original);
    }

    @Test void aHistoricalFailureCanBeRecordedAfterSuccessWithoutChangingMoneyEvidence() {
        PgResult paid = new PgResult("PORTONE_TEST", payment.getPaymentId(), payment.getOrderNo(), payment.getAmount(), "KRW", "pg-success", requested.plusSeconds(3), PgResult.Decision.APPROVED, null, null);
        payment.confirm(paid, id(), requested.plusSeconds(4));
        FailedPaymentAttempt.record(payment, proof, requested.plusSeconds(5));
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PAID); assertThat(payment.supplierResult()).isEqualTo(paid);
    }

    @Test void anotherPurchaseAndAnObservationBeforeFailureCannotCreateHistory() {
        Payment other = Payment.receive(request(), "PORTONE_TEST");
        assertThatThrownBy(() -> FailedPaymentAttempt.record(other, proof, requested.plusSeconds(2))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FailedPaymentAttempt.record(payment, proof, requested)).isInstanceOf(IllegalArgumentException.class);
    }
}
