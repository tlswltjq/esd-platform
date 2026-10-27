package com.stove.order.core.domain;

import static com.stove.order.OrderFixtures.id;
import static com.stove.order.OrderFixtures.order;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class RefundRestorationTest {
    private static final Instant NOW = Instant.parse("2026-10-27T01:00:00.123456Z");
    private Order paid; private Refund refund; private RefundAttempt attempt;
    @Test void failureStartsRestorationAndPreservesTheOriginalEligibilityWithoutPretendingRestorationCompleted() {
        prepare(); RefundFailureConfirmation failure = failure();
        assertThat(refund.recordFailure(paid, attempt, failure, NOW.plusSeconds(1))).isTrue();
        assertThat(refund.getStatus().name()).isEqualTo("PENDING_LICENSE_RESTORATION");
        assertThat(attempt.getStatus()).isEqualTo(RefundAttemptStatus.FAILED);
        assertThat(refund.getCompletedAt()).isNull(); assertThat(refund.isAutomaticRefundEligible()).isTrue();
        assertThat(refund.getCurrentAttemptId()).isEqualTo(attempt.getAttemptId());
        assertThat(refund.recordFailure(paid, attempt, failure, NOW.plusSeconds(2))).isFalse();
    }
    @Test void unrelatedFailureDoesNotAuthorizeRestoration() {
        prepare(); RefundFailureConfirmation original = failure();
        RefundFailureConfirmation other = new RefundFailureConfirmation(original.eventId(), NOW, original.refundId(), id(),
                original.requestEventId(), original.orderNo(), original.paymentId(), original.buyerSubject(), original.amount(),
                original.currency(), original.providerTransactionId(), original.cancellationId(), original.lookupHash());
        assertThatThrownBy(() -> refund.recordFailure(paid, attempt, other, NOW)).isInstanceOf(RefundCompletionRejected.class);
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.PENDING_PAYMENT_REFUND);
    }
    @Test void onlyActualMatchingRestorationCompletesTheFailedWorkflowAndReplayKeepsItsFirstFacts() {
        prepare(); RefundFailureConfirmation failure = failure(); refund.recordFailure(paid, attempt, failure, NOW.plusSeconds(1));
        LicenseRestorationConfirmation restored = restored();
        assertThat(refund.confirmRestoration(paid, attempt, restored)).isTrue();
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.FAILED);
        assertThat(attempt.getRestoredAt()).isEqualTo(NOW.plusSeconds(2));
        assertThat(refund.confirmRestoration(paid, attempt, restored)).isFalse();
        assertThat(refund.getCompletedAt()).isNull(); assertThat(paid.getStatus()).isEqualTo(OrderStatus.PAID);
    }
    @Test void fabricatedCompletionAndADifferentCurrentSuspensionCannotEndRestorationWaiting() {
        prepare(); RefundFailureConfirmation failure = failure(); refund.recordFailure(paid, attempt, failure, NOW.plusSeconds(1));
        LicenseRestorationConfirmation original = restored();
        LicenseRestorationConfirmation other = new LicenseRestorationConfirmation(original.eventId(), original.occurredAt(), original.requestEventId(),
                original.refundId(), original.attemptId(), original.failureEventId(), original.licenseId(), original.orderNo(), original.buyerSubject(),
                original.productId(), original.productCode(), original.paymentId(), id(), original.suspensionEventId());
        assertThatThrownBy(() -> refund.confirmRestoration(paid, attempt, other)).isInstanceOf(RefundCompletionRejected.class);
        assertThat(refund.getStatus().name()).isEqualTo("PENDING_LICENSE_RESTORATION");
    }
    private LicenseRestorationConfirmation restored() { return new LicenseRestorationConfirmation(id(), NOW.plusSeconds(2), attempt.getRestorationRequestEventId(),
            refund.getRefundId(), attempt.getAttemptId(), attempt.getFailureEventId(), attempt.getLicenseId(), paid.getOrderNo(), paid.getBuyerSubject(),
            paid.getProductId(), paid.getProductCode(), paid.getPaymentId(), attempt.getSuspensionRequestEventId(), attempt.getSuspensionEventId()); }

    private RefundFailureConfirmation failure() { return new RefundFailureConfirmation(id(), NOW, refund.getRefundId(), attempt.getAttemptId(),
            attempt.getExecutionRequestEventId(), paid.getOrderNo(), paid.getPaymentId(), paid.getBuyerSubject(), paid.getAmount(), "KRW",
            "pg-original", "cancel-own", "a".repeat(64)); }
    private void prepare() {
        paid = order(); paid.apply(new PaymentResult(id(), NOW.minusSeconds(60), paid.getOrderNo(), paid.getRequestEventId(), id(),
                paid.getBuyerSubject(), paid.getAmount(), "KRW", OrderStatus.PAID, null, null));
        refund = Refund.request(paid, NOW.minusSeconds(30));
        LicenseGrant grant = new LicenseGrant(id(), NOW.minusSeconds(50), id(), paid.getOrderNo(), paid.getBuyerSubject(),
                paid.getProductId(), paid.getProductCode(), paid.getPaymentId());
        refund.confirmLicenseGrant(grant);
        LicenseSuspensionConfirmation suspension = new LicenseSuspensionConfirmation(id(), NOW.minusSeconds(10), refund.getSuspensionRequestEventId(),
                refund.getRefundId(), grant.licenseId(), paid.getOrderNo(), paid.getBuyerSubject(), paid.getProductId(), paid.getProductCode(), paid.getPaymentId());
        attempt = RefundAttempt.afterSuspension(refund, suspension, NOW.minusSeconds(5)); refund.recordExecution(attempt);
    }
}
