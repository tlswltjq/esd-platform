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

class RefundCancellationSuccessTest {
    private static final Instant NOW = Payment.now();
    private final Payment paid = paid();
    private final RefundExecution execution = received();
    private final PgRefundLookupRequest purchase = new PgRefundLookupRequest(paid.getPaymentId(), paid.getOrderNo(), paid.getAmount(),
            "KRW", paid.getProviderTransactionId(), paid.getProviderOccurredAt());

    @Test void directSuccessCompletesMoneyAndTheAttemptWithoutRequiringAQueryOrAnOptionalProviderTime() {
        PgResult original = paid.supplierResult();
        assertThat(execution.confirmCancellationSuccess(paid, success("cancel-1"), id(), NOW)).isTrue();
        assertThat(execution.getStatus().name()).isEqualTo("SUCCEEDED");
        assertThat(execution.getRefundConfirmationSource()).isEqualTo("PG_RESPONSE");
        assertThat(execution.getRefundConfirmationEventId()).isEqualTo(execution.getCancellationSuccessEventId());
        assertThat(execution.getProviderRefundedAt()).isNull();
        assertThat(paid.supplierResult()).isEqualTo(original);
    }

    @Test void aLateResponseEnrichesTheAttemptAndPreservesTheFirstQueryCompletion() {
        String first = id();
        execution.confirmRefund(paid, new PgRefundConfirmation(purchase, List.of(new RefundCancellation("cancel-1", paid.getAmount(), NOW))), first, NOW);
        assertThat(execution.confirmCancellationSuccess(paid, success("cancel-1"), id(), NOW.plusSeconds(1))).isTrue();
        assertThat(execution.getRefundConfirmationEventId()).isEqualTo(first);
        assertThat(execution.getRefundConfirmedAt()).isEqualTo(NOW);
        assertThat(execution.getRefundConfirmationSource()).isEqualTo("PG_QUERY_UNATTRIBUTED");
        assertThat(execution.getStatus().name()).isEqualTo("SUCCEEDED");
    }

    @Test void theFirstSuccessIsImmutableAndAnUnrelatedCancellationCannotReplaceIt() {
        execution.confirmCancellationSuccess(paid, success("cancel-1"), id(), NOW);
        String first = execution.getCancellationSuccessEventId();
        assertThat(execution.confirmCancellationSuccess(paid, success("cancel-1"), id(), NOW.plusSeconds(1))).isFalse();
        assertThat(execution.getCancellationSuccessEventId()).isEqualTo(first);
        assertThatThrownBy(() -> execution.confirmCancellationSuccess(paid, success("cancel-2"), id(), NOW.plusSeconds(1)))
                .isInstanceOf(BusinessException.class);
    }

    @Test void aResponseForADifferentKnownQueryCancellationCannotClaimOurExecutionSucceeded() {
        execution.confirmRefund(paid, new PgRefundConfirmation(purchase, List.of(new RefundCancellation("other", paid.getAmount(), NOW))), id(), NOW);
        assertThatThrownBy(() -> execution.confirmCancellationSuccess(paid, success("cancel-1"), id(), NOW))
                .isInstanceOf(BusinessException.class);
        assertThat(execution.getCancellationSucceededAt()).isNull();
    }

    @Test void aLaterConsistentQueryDoesNotReplaceTheDirectSuccessOrInventItsMissingProviderTime() {
        execution.confirmCancellationSuccess(paid, success("cancel-1"), id(), NOW);
        String first = execution.getRefundConfirmationEventId();
        assertThat(execution.confirmRefund(paid, new PgRefundConfirmation(purchase,
                List.of(new RefundCancellation("cancel-1", paid.getAmount(), NOW))), id(), NOW.plusSeconds(1))).isFalse();
        assertThat(execution.getRefundConfirmationEventId()).isEqualTo(first);
        assertThat(execution.getRefundConfirmationSource()).isEqualTo("PG_RESPONSE");
        assertThat(execution.getProviderRefundedAt()).isNull();
    }

    private Payment paid() {
        Payment payment = Payment.receive(request(), "PORTONE_TEST");
        payment.confirm(new PgResult("PORTONE_TEST", payment.getPaymentId(), payment.getOrderNo(), payment.getAmount(), "KRW",
                "pg-original", NOW.minusSeconds(60), PgResult.Decision.APPROVED, null, null, null), id(), NOW.minusSeconds(30));
        return payment;
    }
    private RefundExecution received() {
        RefundExecution received = RefundExecution.receive(paid, execution(paid), NOW.minusSeconds(10));
        received.prepareCancellation(NOW.minusSeconds(5)); received.freezeCancellationBody("frozen body"); return received;
    }
    private PgRefundCancellationSuccess success(String id) {
        return new PgRefundCancellationSuccess(purchase, execution.getAttemptId(), new RefundCancellation(id, paid.getAmount(), null),
                NOW.minusSeconds(2), 0, 0, "ESD refund " + execution.getRefundId());
    }
}
