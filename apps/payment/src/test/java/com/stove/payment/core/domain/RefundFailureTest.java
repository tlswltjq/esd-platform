package com.stove.payment.core.domain;

import static com.stove.payment.PaymentFixtures.id;
import static com.stove.payment.PaymentFixtures.request;
import static com.stove.payment.RefundFixtures.execution;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class RefundFailureTest {
    private static final Instant NOW = Payment.now();
    private Payment payment;
    private RefundExecution refund;

    @Test void anAcceptedOrFailedResponseAloneDoesNotAuthorizeRestorationButBlocksAnotherPost() {
        prepare(); PgRefundCancellationReceipt receipt = receipt();
        assertThat(refund.recordCancellationReceipt(payment, receipt, NOW)).isTrue();
        assertThat(refund.prepareCancellation(NOW.plusSeconds(1)).maySendAt(NOW.plusSeconds(1))).isFalse();
        assertThat(refund.getStatus().name()).isEqualTo("PENDING");
        assertThat(refund.recordCancellationReceipt(payment, receipt.failed(), NOW.plusSeconds(2))).isTrue();
        assertThat(refund.getStatus().name()).isEqualTo("PENDING");
        assertThat(refund.getLookupSchedule().getNextAt()).isNotNull();
    }
    @Test void aVerifiedFailedCancellationAndUnrefundedPurchaseStopTheExecutionOnce() {
        prepare(); PgRefundCancellationReceipt receipt = receipt();
        refund.recordCancellationReceipt(payment, receipt, NOW);
        PgRefundFailureConfirmation proof = new PgRefundFailureConfirmation(receipt.failed(), "a".repeat(64));
        assertThat(refund.confirmFailure(payment, proof, id(), NOW.plusSeconds(1))).isTrue();
        assertThat(refund.getStatus().name()).isEqualTo("FAILED");
        assertThat(refund.getRefundConfirmedAt()).isNull();
        assertThat(refund.getLookupSchedule().getNextAt()).isNull();
        assertThat(refund.confirmFailure(payment, proof, id(), NOW.plusSeconds(2))).isFalse();
    }
    @Test void anUnrelatedFailedCancellationCannotSupplyMissingCorrelation() {
        prepare();
        assertThatThrownBy(() -> refund.confirmFailure(payment, new PgRefundFailureConfirmation(receipt().failed(), "a".repeat(64)), id(), NOW))
                .isInstanceOf(IllegalArgumentException.class);
        refund.recordCancellationReceipt(payment, receipt(), NOW);
        PgRefundCancellationReceipt another = new PgRefundCancellationReceipt(purchase(), refund.getAttemptId(), "someone-elses-cancel",
                PgRefundCancellationReceipt.State.FAILED, NOW, 0, 0, "ESD refund " + refund.getRefundId());
        assertThatThrownBy(() -> refund.confirmFailure(payment, new PgRefundFailureConfirmation(another, "a".repeat(64)), id(), NOW))
                .isInstanceOf(RuntimeException.class);
        assertThat(refund.getStatus().name()).isEqualTo("PENDING");
    }
    private void prepare() {
        payment = Payment.receive(request(), "PORTONE_TEST");
        payment.confirm(new PgResult("PORTONE_TEST", payment.getPaymentId(), payment.getOrderNo(), payment.getAmount(), "KRW", "pg-original",
                NOW.minusSeconds(60), PgResult.Decision.APPROVED, null, null, null), id(), NOW.minusSeconds(30));
        refund = RefundExecution.receive(payment, execution(payment), NOW);
        refund.prepareCancellation(NOW); refund.freezeCancellationBody("original wire body");
    }
    private PgRefundLookupRequest purchase() { return new PgRefundLookupRequest(payment.getPaymentId(), payment.getOrderNo(), payment.getAmount(),
            "KRW", payment.getProviderTransactionId(), payment.getProviderOccurredAt()); }
    private PgRefundCancellationReceipt receipt() { return new PgRefundCancellationReceipt(purchase(), refund.getAttemptId(), "cancel-own",
            PgRefundCancellationReceipt.State.REQUESTED, NOW, 0, 0, "ESD refund " + refund.getRefundId()); }
}
