package com.stove.payment.core.domain;

import static com.stove.payment.PaymentFixtures.id;
import static com.stove.payment.PaymentFixtures.request;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stove.common.web.error.BusinessException;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class PaymentApprovalTest {
    @Test void observationsPreserveHoldStartAndNeverChangeStatusOrPayload() {
        PaymentApproval approval = approval();
        approval.recordObservation(new ApprovalObservation(ApprovalObservation.State.READY, null, null), at);
        approval.stopPosts("PG_INVALID_EVIDENCE", at);
        approval.recordObservation(new ApprovalObservation(ApprovalObservation.State.PENDING, null, null), at.plusSeconds(2));
        assertThat(approval.getReviewStartedAt()).isEqualTo(at);
        assertThat(approval.getLatestPgState()).isEqualTo(ApprovalObservation.State.PENDING);
        assertThat(approval.getLatestPgObservedAt()).isEqualTo(at.plusSeconds(2));
        assertThat(approval.getStatus()).isEqualTo(PaymentApprovalStatus.REVIEW_REQUIRED);
        assertThat(approval.getProtectedPayload()).isEqualTo("encrypted-body");
    }
    @Test void aPlatformSaleRestrictionKeepsTheOriginalUnadmittedRequestAndClearsAfterAdmission() {
        PaymentApproval approval = approval();
        approval.recordSaleRestriction();
        assertThat(approval.getStatus()).isEqualTo(PaymentApprovalStatus.REQUESTED);
        assertThat(approval.getLastErrorCode()).isEqualTo("ORDER_SALE_RESTRICTED");
        assertThat(approval.getAdmittedAt()).isNull();
        assertThat(approval.getFirstPreparedAt()).isNull();
        assertThat(approval.isPostStopped()).isFalse();
        approval.admit(receipt(approval));
        assertThat(approval.getLastErrorCode()).isNull();
        assertThatThrownBy(approval::recordSaleRestriction).isInstanceOf(IllegalStateException.class);
    }
    private final Payment payment = Payment.receive(request(), "PORTONE_TEST");
    private final Instant at = Payment.now();
    private final PaymentApprovalInput input = new PaymentApprovalInput(id(), payment.getOrderNo(), "tx-owned", new ApprovalToken("secret-token"));
    private PaymentApproval approval() { return PaymentApproval.receive(payment, input, "encrypted-body", at); }
    private OrderAdmissionReceipt receipt(PaymentApproval approval) {
        return new OrderAdmissionReceipt(approval.getApprovalId(), payment.getOrderNo(), payment.getPaymentId(),
                at.plusSeconds(1), approval.getExpiresAt());
    }

    @Test void onlyOneLeaseOwnsTheApprovalAndAStaleWorkerCannotFinishIt() {
        PaymentApproval approval = approval(); String first = id(); String second = id();
        assertThat(approval.claim(first, at, Duration.ofSeconds(60))).isTrue();
        assertThat(approval.claim(second, at.plusSeconds(1), Duration.ofSeconds(60))).isFalse();
        assertThat(approval.claim(second, at.plusSeconds(60), Duration.ofSeconds(60))).isTrue();
        assertThat(approval.finish(first, at.plusSeconds(61), at.plusSeconds(66))).isFalse();
        assertThat(approval.getLeaseToken()).isEqualTo(second);
    }
    @Test void postNeedsAdmissionAndTheWindowStartsAtTheFirstPreparationOnly() {
        PaymentApproval approval = approval(); approval.claim(id(), at, Duration.ofHours(4));
        assertThat(approval.canPost(at)).isFalse();
        approval.admit(receipt(approval));
        Instant prepared = at.plusSeconds(120); approval.prepare(prepared);
        approval.prepare(prepared.plusSeconds(30));
        assertThat(approval.getFirstPreparedAt()).isEqualTo(prepared);
        assertThat(approval.canPost(prepared.plusSeconds(10799))).isTrue();
        assertThat(approval.canPost(prepared.plus(Duration.ofHours(3)))).isFalse();
        assertThat(approval.getProtectedPayload()).isEqualTo("encrypted-body");
    }
    @Test void preparationWithoutAnOrderReceiptIsRejected() {
        assertThatThrownBy(() -> approval().prepare(at)).isInstanceOf(IllegalStateException.class);
    }
    @Test void expiryWithoutAdmissionPurgesTheTokenButDoesNotInventAFinancialFailure() {
        PaymentApproval approval = approval(); approval.expire(at.plusSeconds(10));
        assertThat(approval.getStatus()).isEqualTo(PaymentApprovalStatus.EXPIRED);
        assertThat(approval.getProtectedPayload()).isNull();
        assertThat(approval.getNextAt()).isNull();
    }
    @Test void anAdmittedApprovalCannotExpireJustBecauseThirtyMinutesPassed() {
        PaymentApproval approval = approval(); approval.admit(receipt(approval));
        assertThatThrownBy(() -> approval.expire(approval.getExpiresAt())).isInstanceOf(IllegalStateException.class);
    }
    @Test void fixedCommandIdentityIncludesTheTokenButNeverPrintsIt() {
        PaymentApproval approval = approval(); approval.requireSameInput(payment, input);
        PaymentApprovalInput changed = new PaymentApprovalInput(input.requestId(), input.orderNo(), input.supplierAttemptId(), new ApprovalToken("other-token"));
        assertThatThrownBy(() -> approval.requireSameInput(payment, changed)).isInstanceOf(BusinessException.class);
        assertThat(input.toString() + approval.toString()).doesNotContain("secret-token", "encrypted-body");
    }
    @Test void aTerminalApprovalKeepsItsOriginalResolutionAndCannotChangeItsOutcome() {
        PaymentApproval approval=approval(); Instant expiredAt=at.plusSeconds(10); approval.expire(expiredAt);
        approval.expire(expiredAt.plusSeconds(5));
        assertThat(approval.getResolvedAt()).isEqualTo(expiredAt);
        assertThatThrownBy(()->approval.failBeforeAdmission(at,expiredAt.plusSeconds(6))).isInstanceOf(IllegalArgumentException.class);
        assertThat(approval.getStatus()).isEqualTo(PaymentApprovalStatus.EXPIRED);
    }
    @Test void aHardStopKeepsLookupActiveAndPreventsFurtherPosts() {
        PaymentApproval approval = approval(); String lease = id(); approval.claim(lease, at, Duration.ofMinutes(5));
        approval.admit(receipt(approval)); approval.prepare(at.plusSeconds(2)); approval.stopPosts("PG_REQUEST_REJECTED");
        assertThat(approval.canPost(at.plusSeconds(3))).isFalse();
        assertThat(approval.getStatus()).isEqualTo(PaymentApprovalStatus.REVIEW_REQUIRED);
        assertThat(approval.finish(lease, at.plusSeconds(3), at.plusSeconds(8))).isTrue();
        assertThat(approval.getNextAt()).isEqualTo(at.plusSeconds(8));
        assertThat(approval.terminal()).isFalse();
    }
    /** Decision 124: only an admitted, unstopped approval past first preparation + 3 hours with the PG PENDING or READY is held. */
    @Test void anAdmittedApprovalPastTheConfirmWindowWithAnUnsettledPgIsHeldWithoutClosing() {
        ApprovalObservation pending = new ApprovalObservation(ApprovalObservation.State.PENDING, null, null);
        ApprovalObservation ready = new ApprovalObservation(ApprovalObservation.State.READY, null, null);
        assertThat(approval().holdAfterConfirmWindow(pending, at.plusSeconds(20000))).isFalse();
        PaymentApproval approval = approval(); approval.admit(receipt(approval));
        Instant prepared = at.plusSeconds(2); approval.prepare(prepared);
        Instant window = prepared.plus(PaymentApproval.CONFIRM_WINDOW);
        assertThat(approval.holdAfterConfirmWindow(pending, window.minusNanos(1000))).isFalse();
        assertThat(approval.getStatus()).isEqualTo(PaymentApprovalStatus.ADMITTED);
        assertThat(approval.holdAfterConfirmWindow(new ApprovalObservation(ApprovalObservation.State.FAILED, null, window), window)).isFalse();
        assertThat(approval.holdAfterConfirmWindow(pending, window)).isTrue();
        assertThat(approval.getStatus()).isEqualTo(PaymentApprovalStatus.REVIEW_REQUIRED);
        assertThat(approval.getLastErrorCode()).isEqualTo("CONFIRM_WINDOW_EXPIRED");
        assertThat(approval.getReviewStartedAt()).isEqualTo(window);
        assertThat(approval.isPostStopped()).isTrue(); assertThat(approval.terminal()).isFalse();
        assertThat(approval.getProtectedPayload()).isEqualTo("encrypted-body");
        assertThat(approval.holdAfterConfirmWindow(ready, window.plusSeconds(60))).isFalse();
        assertThat(approval.getReviewStartedAt()).isEqualTo(window);
        PaymentApproval rejected = approval(); rejected.admit(receipt(rejected)); rejected.prepare(prepared);
        rejected.stopPosts("PG_REQUEST_REJECTED", prepared);
        assertThat(rejected.holdAfterConfirmWindow(ready, window)).isFalse();
        assertThat(rejected.getLastErrorCode()).isEqualTo("PG_REQUEST_REJECTED");
    }
    /** D-049: a failed lookup past the window holds from the last valid PENDING/READY observation, and never without one. */
    @Test void aFailedLookupPastTheConfirmWindowHoldsFromTheLastValidObservationOnly() {
        PaymentApproval unobserved = approval(); unobserved.admit(receipt(unobserved));
        Instant prepared = at.plusSeconds(2); unobserved.prepare(prepared);
        Instant window = prepared.plus(PaymentApproval.CONFIRM_WINDOW);
        assertThat(unobserved.holdAfterConfirmWindowWithoutLookup(window.plusSeconds(60))).isFalse();
        assertThat(unobserved.getStatus()).isEqualTo(PaymentApprovalStatus.ADMITTED);
        PaymentApproval approval = approval(); approval.admit(receipt(approval)); approval.prepare(prepared);
        approval.recordObservation(new ApprovalObservation(ApprovalObservation.State.PENDING, null, null), prepared);
        approval.recordInspectionFailure("UNAVAILABLE", window.minusSeconds(5));
        assertThat(approval.holdAfterConfirmWindowWithoutLookup(window.minusNanos(1000))).isFalse();
        assertThat(approval.holdAfterConfirmWindowWithoutLookup(window)).isTrue();
        assertThat(approval.getStatus()).isEqualTo(PaymentApprovalStatus.REVIEW_REQUIRED);
        assertThat(approval.getLastErrorCode()).isEqualTo("CONFIRM_WINDOW_EXPIRED"); assertThat(approval.getReviewStartedAt()).isEqualTo(window);
        assertThat(approval.getLatestPgState()).isEqualTo(ApprovalObservation.State.PENDING);
        assertThat(approval.holdAfterConfirmWindowWithoutLookup(window.plusSeconds(60))).isFalse();
        PaymentApproval failed = approval(); failed.admit(receipt(failed)); failed.prepare(prepared);
        failed.recordObservation(new ApprovalObservation(ApprovalObservation.State.FAILED, null, prepared), prepared);
        assertThat(failed.holdAfterConfirmWindowWithoutLookup(window)).isFalse();
    }
    @Test void aLaterInvalidLookupCannotHideAnObservedUnadmittedPayment() {
        PaymentApproval approval = approval();
        approval.stopPosts("PG_INVALID_EVIDENCE");
        approval.stopPosts("PAID_WITHOUT_ADMISSION");
        approval.stopPosts("PG_INVALID_EVIDENCE");
        assertThat(approval.getLastErrorCode()).isEqualTo("PAID_WITHOUT_ADMISSION");
        assertThat(approval.getStatus()).isEqualTo(PaymentApprovalStatus.REVIEW_REQUIRED);
    }
}
