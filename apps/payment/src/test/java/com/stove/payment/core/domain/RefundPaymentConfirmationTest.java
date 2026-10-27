package com.stove.payment.core.domain;

import static com.stove.payment.PaymentFixtures.id;
import static com.stove.payment.PaymentFixtures.request;
import static com.stove.payment.RefundFixtures.execution;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stove.common.web.error.BusinessException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class RefundPaymentConfirmationTest {
    private final Instant now = Payment.now().plusSeconds(1);
    private final Payment payment = paid();
    private final RefundExecution execution = RefundExecution.receive(payment, execution(payment), now);

    @Test void confirmedMoneyDoesNotInventTheSuccessOfOurAttemptOrRewriteThePurchase() {
        PgResult original = payment.supplierResult();
        assertThat(execution.confirmRefund(payment, confirmation(payment), id(), now.plusSeconds(10))).isTrue();
        assertThat(execution.getStatus()).isEqualTo(RefundExecutionStatus.PENDING);
        assertThat(payment.supplierResult()).isEqualTo(original);
        assertThatThrownBy(() -> execution.prepareCancellation(now.plusSeconds(11))).isInstanceOf(BusinessException.class);
    }

    @Test void completionCanBeReconciledAfterTheAutomaticResendWindowHasClosed() {
        execution.prepareCancellation(now);
        execution.prepareCancellation(now.plusSeconds(10800));
        assertThat(execution.confirmRefund(payment, confirmation(payment), id(), now.plusSeconds(10801))).isTrue();
        assertThat(execution.getAutomaticCancellationStoppedAt()).isEqualTo(now.plusSeconds(10800));
    }

    @Test void duplicateEvidenceDoesNotReplaceTheFirstConfirmationAndChangedEvidenceIsRejected() {
        assertThat(execution.confirmRefund(payment, confirmation(payment), id(), now.plusSeconds(10))).isTrue();
        assertThat(execution.confirmRefund(payment, confirmation(payment), id(), now.plusSeconds(20))).isFalse();
        PgRefundConfirmation changed = new PgRefundConfirmation(purchase(payment), List.of(
                new RefundCancellation("different-cancellation", payment.getAmount(), now.plusSeconds(5))));
        assertThatThrownBy(() -> execution.confirmRefund(payment, changed, id(), now.plusSeconds(20)))
                .isInstanceOf(BusinessException.class);
    }

    @Test void anotherPurchaseCannotConfirmThisRefund() {
        assertThatThrownBy(() -> execution.confirmRefund(payment, confirmation(paid()), id(), now.plusSeconds(10)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void fullEvidenceCanIncludeSeveralSuccessfulPartialCancellationsButNoDuplicateIds() {
        PgRefundLookupRequest purchase = purchase(payment);
        long first = payment.getAmount() - 1;
        PgRefundConfirmation confirmation = new PgRefundConfirmation(purchase, List.of(
                new RefundCancellation("cancel-b", 1, now), new RefundCancellation("cancel-a", first, now)));
        assertThat(confirmation.cancellations()).extracting(RefundCancellation::getCancellationId).containsExactly("cancel-a", "cancel-b");
        assertThatThrownBy(() -> new PgRefundConfirmation(purchase, List.of(new RefundCancellation("same", first, now),
                new RefundCancellation("same", 1, now)))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PgRefundConfirmation(purchase, List.of(new RefundCancellation("partial", first, now))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private Payment paid() {
        Payment paid = Payment.receive(request(), "PORTONE_TEST");
        paid.confirm(new PgResult("PORTONE_TEST", paid.getPaymentId(), paid.getOrderNo(), paid.getAmount(), "KRW",
                "pg-original", Instant.parse("2026-10-16T00:00:00.123456Z"), PgResult.Decision.APPROVED,
                null, null, null), id(), Payment.now());
        return paid;
    }

    private PgRefundLookupRequest purchase(Payment paid) {
        return new PgRefundLookupRequest(paid.getPaymentId(), paid.getOrderNo(), paid.getAmount(), paid.getCurrency(),
                paid.getProviderTransactionId(), paid.getProviderOccurredAt());
    }

    private PgRefundConfirmation confirmation(Payment paid) {
        return new PgRefundConfirmation(purchase(paid), List.of(new RefundCancellation("cancel-1", paid.getAmount(), now.plusSeconds(5))));
    }
}
