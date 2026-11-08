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
        // Decision 128: the read-only lookup continues at a low frequency after the failure.
        assertThat(refund.getLookupSchedule().getNextAt()).isNotNull();
        assertThat(refund.getCancellationSchedule().getNextAt()).isNull();
        assertThat(refund.confirmFailure(payment, proof, id(), NOW.plusSeconds(2))).isFalse();
    }

    /** Decision 128: only an unconfirmed execution whose automatic POST stopped is a failure-closure target. */
    @Test void onlyAStoppedUnconfirmedExecutionCanBeClosedAsFailed() {
        prepare();
        Instant deadline = NOW.plusSeconds(10800);
        assertThat(refund.failureClosableAt(deadline.minusNanos(1000))).as("automatic POST still allowed").isFalse();
        assertThatThrownBy(() -> refund.closeAsFailed(payment, id(), "b".repeat(64), deadline.minusNanos(1000)))
                .isInstanceOf(BusinessException.class);
        assertThat(refund.failureClosableAt(deadline)).as("window ended").isTrue();
        RefundExecution blocked = RefundExecution.receive(payment, execution(payment), NOW);
        blocked.prepareCancellation(NOW); blocked.blockAutomaticCancellation(NOW.plusSeconds(1));
        assertThat(blocked.failureClosableAt(NOW.plusSeconds(2))).as("balance change").isTrue();
        RefundExecution unprepared = RefundExecution.receive(payment, execution(payment), NOW);
        assertThat(unprepared.failureClosableAt(deadline)).isFalse();
        refund.closeAsFailed(payment, "failure-event", "b".repeat(64), deadline);
        assertThat(refund.getStatus()).isEqualTo(RefundExecutionStatus.FAILED);
        assertThat(refund.getFailureEventId()).isEqualTo("failure-event"); assertThat(refund.getFailureConfirmedAt()).isEqualTo(deadline);
        assertThat(refund.getFailureLookupHash()).isEqualTo("b".repeat(64));
        assertThat(refund.getLookupSchedule().getNextAt()).as("watched after the failure").isNotNull();
        assertThat(refund.failureClosableAt(deadline)).as("already failed").isFalse();
    }

    /** Decision 128: a late full cancellation is recorded once and stops the lookup; status and money facts do not change. */
    @Test void aLateFullCancellationIsRecordedForInvestigationOnly() {
        prepare();
        Instant deadline = NOW.plusSeconds(10800);
        assertThatThrownBy(() -> refund.recordLateCancellation(payment, confirmation(), deadline.plusSeconds(600))).isInstanceOf(IllegalStateException.class);
        refund.closeAsFailed(payment, "failure-event", "b".repeat(64), deadline);
        assertThat(refund.recordLateCancellation(payment, confirmation(), deadline.plusSeconds(600))).isTrue();
        assertThat(refund.getStatus()).isEqualTo(RefundExecutionStatus.FAILED);
        assertThat(refund.getLateCancellationObservedAt()).isEqualTo(deadline.plusSeconds(600));
        assertThat(refund.getLateCancelledAt()).isEqualTo(deadline.plusSeconds(300));
        assertThat(refund.getRefundConfirmedAt()).isNull(); assertThat(refund.getConfirmedCancellations()).isEmpty();
        assertThat(refund.getLookupSchedule().getNextAt()).isNull();
        assertThat(refund.recordLateCancellation(payment, confirmation(), deadline.plusSeconds(1200))).isFalse();
        assertThat(refund.getLateCancellationObservedAt()).isEqualTo(deadline.plusSeconds(600));
    }

    private PgRefundConfirmation confirmation() {
        return new PgRefundConfirmation(PgRefundLookupRequest.from(payment), List.of(new RefundCancellation("console-1", payment.getAmount(), NOW.plusSeconds(10800 + 300))));
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
