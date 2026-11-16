package com.stove.order.core.domain;

import static com.stove.order.OrderFixtures.id;
import static com.stove.order.OrderFixtures.order;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class RefundRestorationTest {
    private static final Instant GRANT_CONFIRMED_AT = Instant.parse("2026-10-26T01:00:00.123456Z");
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
        RefundFailureConfirmation other = RefundFailureConfirmation.pgCancellationFailed(original.eventId(), NOW, original.refundId(), id(),
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
    @Test void restorationThatReturnedThePurchaseToStorageIsShownOnTheRefund() {
        prepare(); refund.recordFailure(paid, attempt, failure(), NOW.plusSeconds(1));
        assertThat(refund.isPurchaseReturnedToStorage()).isFalse();
        assertThat(refund.confirmRestoration(paid, attempt, restored(true))).isTrue();
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.FAILED);
        assertThat(refund.isPurchaseReturnedToStorage()).isTrue();
    }
    @Test void fabricatedCompletionAndADifferentCurrentSuspensionCannotEndRestorationWaiting() {
        prepare(); RefundFailureConfirmation failure = failure(); refund.recordFailure(paid, attempt, failure, NOW.plusSeconds(1));
        LicenseRestorationConfirmation original = restored();
        LicenseRestorationConfirmation other = new LicenseRestorationConfirmation(original.eventId(), original.occurredAt(), original.requestEventId(),
                original.refundId(), original.attemptId(), original.failureEventId(), original.licenseId(), original.orderNo(), original.buyerSubject(),
                original.productId(), original.productCode(), original.paymentId(), id(), original.suspensionEventId(), false);
        assertThatThrownBy(() -> refund.confirmRestoration(paid, attempt, other)).isInstanceOf(RefundCompletionRejected.class);
        assertThat(refund.getStatus().name()).isEqualTo("PENDING_LICENSE_RESTORATION");
    }
    @Test void explicitRetryPreservesFirstEligibilityAndStartsASecondExecutionOnlyAfterAnotherActualSuspension() {
        prepare(); RefundFailureConfirmation failed = failure(); refund.recordFailure(paid, attempt, failed, NOW.plusSeconds(1));
        refund.confirmRestoration(paid, attempt, restored());
        Instant originalReceipt = refund.getReceivedAt(); String originalStop = refund.getSuspensionRequestEventId();
        assertThat(refund.requestRetry(paid, attempt, NOW.plusSeconds(86400))).isTrue();
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.PENDING_LICENSE_SUSPENSION);
        assertThat(refund.getCurrentAttemptId()).isEqualTo(attempt.getAttemptId());
        assertThat(refund.getReceivedAt()).isEqualTo(originalReceipt); assertThat(refund.isAutomaticRefundEligible()).isTrue();
        assertThat(refund.getSuspensionRequestEventId()).isNotEqualTo(originalStop);
        assertThat(refund.requestRetry(paid, attempt, NOW.plusSeconds(86401))).isFalse();
        RefundAttempt next = second();
        assertThat(next.getAttemptNo()).isEqualTo(2); assertThat(next.getPreviousAttemptId()).isEqualTo(attempt.getAttemptId());
        assertThat(next.getAttemptId()).isNotEqualTo(attempt.getAttemptId());
        assertThat(attempt.getStatus()).isEqualTo(RefundAttemptStatus.FAILED); assertThat(attempt.getFailureEventId()).isEqualTo(failed.eventId());
    }
    @Test void pendingExecutionAndRestorationRequestAloneCannotAuthorizeAnotherAttempt() {
        prepare(); assertThatThrownBy(() -> refund.requestRetry(paid, attempt, NOW.plusSeconds(86400))).isInstanceOf(RuntimeException.class);
        refund.recordFailure(paid, attempt, failure(), NOW.plusSeconds(1));
        assertThatThrownBy(() -> refund.requestRetry(paid, attempt, NOW.plusSeconds(86400))).isInstanceOf(RuntimeException.class);
        assertThat(attempt.getRetryRequestedAt()).isNull();
    }
    @Test void knownOldFailureAndRestorationReplaysCannotReplaceTheNewSuspensionOrExecution() {
        prepare(); RefundFailureConfirmation failed = failure(); refund.recordFailure(paid, attempt, failed, NOW.plusSeconds(1));
        LicenseRestorationConfirmation completed = restored(); refund.confirmRestoration(paid, attempt, completed);
        refund.requestRetry(paid, attempt, NOW.plusSeconds(86400)); String stop = refund.getSuspensionRequestEventId();
        assertThat(refund.recordFailure(paid, attempt, failed, NOW.plusSeconds(86401))).isFalse();
        assertThat(refund.confirmRestoration(paid, attempt, completed)).isFalse();
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.PENDING_LICENSE_SUSPENSION); assertThat(refund.getSuspensionRequestEventId()).isEqualTo(stop);
        RefundAttempt next = second();
        assertThat(refund.recordFailure(paid, attempt, failed, NOW.plusSeconds(86403))).isFalse();
        assertThat(refund.confirmRestoration(paid, attempt, completed)).isFalse();
        assertThat(refund.getCurrentAttemptId()).isEqualTo(next.getAttemptId());
    }
    @Test void lateRedeliveryOfTheFirstRetryCannotCreateAThirdAttemptAfterTheSecondAttemptAlsoFailedAndWasRestored() {
        prepare(); refund.recordFailure(paid, attempt, failure(), NOW.plusSeconds(1)); refund.confirmRestoration(paid, attempt, restored());
        refund.requestRetry(paid, attempt, NOW.plusSeconds(86400)); RefundAttempt next = second();
        RefundFailureConfirmation failed = RefundFailureConfirmation.pgCancellationFailed(id(), NOW.plusSeconds(86403), refund.getRefundId(), next.getAttemptId(),
                next.getExecutionRequestEventId(), paid.getOrderNo(), paid.getPaymentId(), paid.getBuyerSubject(), paid.getAmount(), "KRW", "pg-original", "cancel-second", "b".repeat(64));
        refund.recordFailure(paid, next, failed, NOW.plusSeconds(86404));
        LicenseRestorationConfirmation restored = new LicenseRestorationConfirmation(id(), NOW.plusSeconds(86405), next.getRestorationRequestEventId(),
                refund.getRefundId(), next.getAttemptId(), next.getFailureEventId(), next.getLicenseId(), paid.getOrderNo(), paid.getBuyerSubject(),
                paid.getProductId(), paid.getProductCode(), paid.getPaymentId(), next.getSuspensionRequestEventId(), next.getSuspensionEventId(), false);
        refund.confirmRestoration(paid, next, restored);
        assertThat(refund.requestRetry(paid, attempt, NOW.plusSeconds(172800))).isFalse();
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.FAILED); assertThat(next.getRetryRequestedAt()).isNull();
        assertThat(refund.requestRetry(paid, next, NOW.plusSeconds(172800))).isTrue();
        assertThat(next.getRetryRequestedAt()).isEqualTo(NOW.plusSeconds(172800));
    }

    /**
     * 결정 148: 운영자 실패 종결은 PG 확정 실패와 같은 업무 결과(복구 대기·복구 요청)를 내고, 출처와 종결 ID를 시도에 남긴다.
     * 같은 종결의 재전달은 바꾸지 않고, 같은 시도에 다른 실패 근거(PG 확정 실패 포함)는 받지 않는다.
     */
    @Test void anOperatorFailureClosureFailsTheAttemptLikeAPgFailureAndRecordsItsSourceAndClosure() {
        prepare(); String closureId = id(); RefundFailureConfirmation closed = closure(closureId, null);
        assertThat(refund.recordFailure(paid, attempt, closed, NOW.plusSeconds(1))).isTrue();
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.PENDING_LICENSE_RESTORATION);
        assertThat(attempt.getStatus()).isEqualTo(RefundAttemptStatus.FAILED);
        assertThat(attempt.getFailureReasonCode()).isEqualTo(RefundAttempt.OPERATOR_FAILURE_CLOSED);
        assertThat(RefundFailureReason.fromAttemptCode(attempt.getFailureReasonCode())).contains(RefundFailureReason.PAYMENT_NOT_CANCELLED);
        assertThat(attempt.getFailureClosureId()).isEqualTo(closureId);
        assertThat(attempt.getFailureEventId()).isEqualTo(closed.eventId());
        assertThat(attempt.getRestorationRequestEventId()).isNotNull(); assertThat(attempt.getRestorationRequestedAt()).isEqualTo(NOW.plusSeconds(1));
        assertThat(refund.recordFailure(paid, attempt, closed, NOW.plusSeconds(2))).as("재전달").isFalse();
        RefundFailureConfirmation pg = RefundFailureConfirmation.pgCancellationFailed(closed.eventId(), closed.occurredAt(), closed.refundId(),
                closed.attemptId(), closed.requestEventId(), closed.orderNo(), closed.paymentId(), closed.buyerSubject(), closed.amount(),
                closed.currency(), closed.providerTransactionId(), "cancel-own", closed.lookupHash());
        assertThat(pg.fingerprint()).isNotEqualTo(closed.fingerprint());
        assertThatThrownBy(() -> refund.recordFailure(paid, attempt, pg, NOW.plusSeconds(3))).isInstanceOf(RefundCompletionRejected.class);
        assertThatThrownBy(() -> refund.recordFailure(paid, attempt, closure(id(), "cancel-own"), NOW.plusSeconds(3))).isInstanceOf(RefundCompletionRejected.class);
        assertThat(attempt.getFailureClosureId()).isEqualTo(closureId);
    }

    @Test void aPgFailureRecordsNoClosureAndKeepsItsStoredCode() {
        prepare();
        assertThat(refund.recordFailure(paid, attempt, failure(), NOW.plusSeconds(1))).isTrue();
        assertThat(attempt.getFailureReasonCode()).isEqualTo(RefundAttempt.PG_CANCELLATION_FAILED);
        assertThat(attempt.getFailureClosureId()).isNull();
    }

    /** 출처마다 근거가 다르다. 한 출처의 칸을 다른 출처의 값으로 채우지 않는다. */
    @Test void eachFailureSourceRequiresItsOwnEvidence() {
        prepare(); RefundFailureConfirmation pg = failure();
        assertThatThrownBy(() -> RefundFailureConfirmation.pgCancellationFailed(pg.eventId(), pg.occurredAt(), pg.refundId(), pg.attemptId(),
                pg.requestEventId(), pg.orderNo(), pg.paymentId(), pg.buyerSubject(), pg.amount(), pg.currency(), pg.providerTransactionId(), null, pg.lookupHash()))
                .as("PG 확정 실패의 취소 ID").isInstanceOf(RefundCompletionRejected.class);
        assertThatThrownBy(() -> new RefundFailureConfirmation(pg.eventId(), pg.occurredAt(), pg.refundId(), pg.attemptId(), pg.requestEventId(),
                pg.orderNo(), pg.paymentId(), pg.buyerSubject(), pg.amount(), pg.currency(), pg.providerTransactionId(), pg.cancellationId(), pg.lookupHash(),
                RefundFailureSource.PG_CANCELLATION_FAILED, id(), null)).as("PG 확정 실패에 종결 ID").isInstanceOf(RefundCompletionRejected.class);
        assertThatThrownBy(() -> closure("not-a-closure-id", null)).isInstanceOf(RefundCompletionRejected.class);
        assertThatThrownBy(() -> RefundFailureConfirmation.operatorClosed(id(), NOW, refund.getRefundId(), attempt.getAttemptId(),
                attempt.getExecutionRequestEventId(), paid.getOrderNo(), paid.getPaymentId(), paid.getBuyerSubject(), paid.getAmount(), "KRW",
                "pg-original", null, "b".repeat(64), id(), NOW.plusNanos(1000))).as("관측이 종결보다 늦음").isInstanceOf(RefundCompletionRejected.class);
        assertThatThrownBy(() -> new RefundFailureConfirmation(pg.eventId(), pg.occurredAt(), pg.refundId(), pg.attemptId(), pg.requestEventId(),
                pg.orderNo(), pg.paymentId(), pg.buyerSubject(), pg.amount(), pg.currency(), pg.providerTransactionId(), pg.cancellationId(), pg.lookupHash(),
                null, null, null)).as("출처 없음").isInstanceOf(RefundCompletionRejected.class);
    }

    private RefundFailureConfirmation closure(String closureId, String failedCancellationId) {
        return RefundFailureConfirmation.operatorClosed(id(), NOW, refund.getRefundId(), attempt.getAttemptId(), attempt.getExecutionRequestEventId(),
                paid.getOrderNo(), paid.getPaymentId(), paid.getBuyerSubject(), paid.getAmount(), "KRW", "pg-original", failedCancellationId,
                "b".repeat(64), closureId, NOW.minusSeconds(1));
    }

    private RefundAttempt second() {
        LicenseSuspensionConfirmation stop = new LicenseSuspensionConfirmation(id(), NOW.plusSeconds(86401), refund.getSuspensionRequestEventId(),
                refund.getRefundId(), attempt.getLicenseId(), paid.getOrderNo(), paid.getBuyerSubject(), paid.getProductId(), paid.getProductCode(), paid.getPaymentId());
        RefundAttempt next = RefundAttempt.afterRetrySuspension(refund, attempt, stop, NOW.plusSeconds(86402));
        assertThat(next).isNotNull(); refund.recordExecution(next); return next;
    }

    private LicenseRestorationConfirmation restored() { return restored(false); }
    private LicenseRestorationConfirmation restored(boolean returnedToStorage) { return new LicenseRestorationConfirmation(id(), NOW.plusSeconds(2), attempt.getRestorationRequestEventId(),
            refund.getRefundId(), attempt.getAttemptId(), attempt.getFailureEventId(), attempt.getLicenseId(), paid.getOrderNo(), paid.getBuyerSubject(),
            paid.getProductId(), paid.getProductCode(), paid.getPaymentId(), attempt.getSuspensionRequestEventId(), attempt.getSuspensionEventId(), returnedToStorage); }

    private RefundFailureConfirmation failure() { return RefundFailureConfirmation.pgCancellationFailed(id(), NOW, refund.getRefundId(), attempt.getAttemptId(),
            attempt.getExecutionRequestEventId(), paid.getOrderNo(), paid.getPaymentId(), paid.getBuyerSubject(), paid.getAmount(), "KRW",
            "pg-original", "cancel-own", "a".repeat(64)); }
    private void prepare() {
        paid = order(); paid.apply(new PaymentResult(id(), NOW.minusSeconds(60), paid.getOrderNo(), paid.getRequestEventId(), id(),
                paid.getBuyerSubject(), paid.getAmount(), "KRW", OrderStatus.PAID, null, null));
        refund = Refund.request(paid, NOW.minusSeconds(30));
        LicenseGrant grant = new LicenseGrant(id(), NOW.minusSeconds(50), id(), paid.getOrderNo(), paid.getBuyerSubject(),
                paid.getProductId(), paid.getProductCode(), paid.getPaymentId());
        refund.confirmLicenseGrant(grant, GRANT_CONFIRMED_AT);
        LicenseSuspensionConfirmation suspension = new LicenseSuspensionConfirmation(id(), NOW.minusSeconds(10), refund.getSuspensionRequestEventId(),
                refund.getRefundId(), grant.licenseId(), paid.getOrderNo(), paid.getBuyerSubject(), paid.getProductId(), paid.getProductCode(), paid.getPaymentId());
        attempt = RefundAttempt.afterSuspension(refund, suspension, NOW.minusSeconds(5)); refund.recordExecution(attempt);
    }
}
