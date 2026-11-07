package com.stove.payment.core.domain;

import static com.stove.payment.PaymentFixtures.id;
import static com.stove.payment.PaymentFixtures.request;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stove.common.web.error.BusinessException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Decision 115: a held approval closes only on re-inspected PG evidence and ends the purchase without moving money. */
class ApprovalClosureTest {
    private final Payment payment = Payment.receive(request(), "PORTONE_TEST");
    private final Instant at = Payment.now();
    private static final ApprovalObservation CANCELLED = new ApprovalObservation(ApprovalObservation.State.CANCELLED, null, null);
    private static final ApprovalObservation READY = new ApprovalObservation(ApprovalObservation.State.READY, null, null);
    private static final ApprovalObservation PENDING = new ApprovalObservation(ApprovalObservation.State.PENDING, null, null);

    @Test void onlyFullCancellationDeclineOrAnUnconfirmedReadyCanCloseAHold() {
        ApprovalObservation failed = new ApprovalObservation(ApprovalObservation.State.FAILED, null, at);
        ApprovalObservation paid = new ApprovalObservation(ApprovalObservation.State.PAID, new PgResult("PORTONE_TEST", payment.getPaymentId(),
                payment.getOrderNo(), payment.getAmount(), payment.getCurrency(), "pg-tx", at, PgResult.Decision.APPROVED, null, null), null);
        assertThat(List.of(CANCELLED, failed, READY)).allMatch(ApprovalObservation::closesReview);
        assertThat(List.of(paid, PENDING)).noneMatch(ApprovalObservation::closesReview);
        assertThatThrownBy(() -> new ApprovalObservation(ApprovalObservation.State.CANCELLED, null, at)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void closureClaimUsesTheWorkerLeaseWithoutWaitingForTheNextLookup() {
        PaymentApproval approval = held("PG_INVALID_EVIDENCE");
        String worker = id(); String closure = id();
        assertThat(approval.claim(worker, at, Duration.ofSeconds(60))).isTrue();
        assertThat(approval.finish(worker, at.plusSeconds(1), at.plusSeconds(30))).isTrue();
        // The worker would only run again at +30s; an operator does not have to wait for it.
        assertThat(approval.claimForClosure(closure, at.plusSeconds(2), Duration.ofSeconds(60))).isTrue();
        assertThat(approval.owns(closure, at.plusSeconds(3))).isTrue();
        assertThat(approval.getNextAt()).isEqualTo(approval.getLeaseUntil());
        assertThat(approval.claim(id(), at.plusSeconds(31), Duration.ofSeconds(60))).isFalse();
        assertThat(approval.claimForClosure(id(), at.plusSeconds(3), Duration.ofSeconds(60))).isFalse();
        assertThat(approval.getLeaseToken()).isEqualTo(closure);
    }

    @Test void anActiveWorkerLeaseRefusesTheClosureClaim() {
        PaymentApproval approval = held("PG_REQUEST_REJECTED");
        String worker = id();
        assertThat(approval.claim(worker, at, Duration.ofSeconds(60))).isTrue();
        assertThat(approval.claimForClosure(id(), at.plusSeconds(59), Duration.ofSeconds(60))).isFalse();
        assertThat(approval.getLeaseToken()).isEqualTo(worker);
        assertThat(approval.claimForClosure(id(), at.plusSeconds(60), Duration.ofSeconds(60))).isTrue();
    }

    @Test void onlyAHeldApprovalCanBeClaimedForClosure() {
        PaymentApproval requested = approval();
        assertThatThrownBy(() -> requested.claimForClosure(id(), at, Duration.ofSeconds(60))).isInstanceOfSatisfying(BusinessException.class,
                failure -> assertThat(failure.errorCode()).isEqualTo(PaymentErrorCode.APPROVAL_NOT_HELD));
        PaymentApproval expired = approval(); expired.expire(at.plusSeconds(1));
        assertThatThrownBy(() -> expired.claimForClosure(id(), at.plusSeconds(2), Duration.ofSeconds(60))).isInstanceOf(BusinessException.class);
    }

    @Test void eachClosableEvidenceEndsTheHoldAsFailedKeepsTheReasonAndStopsScheduling() {
        for (ApprovalObservation evidence : List.of(CANCELLED, READY, new ApprovalObservation(ApprovalObservation.State.FAILED, null, at))) {
            PaymentApproval approval = held("PAID_WITHOUT_ADMISSION");
            String lease = id();
            approval.claimForClosure(lease, at.plusSeconds(1), Duration.ofSeconds(60));
            approval.closeAfterReview(evidence, lease, at.plusSeconds(2));
            assertThat(approval.getStatus()).isEqualTo(PaymentApprovalStatus.FAILED);
            assertThat(approval.terminal()).isTrue();
            assertThat(approval.getLastErrorCode()).isEqualTo("PAID_WITHOUT_ADMISSION");
            assertThat(approval.getResolvedAt()).isEqualTo(at.plusSeconds(2));
            assertThat(approval.getNextAt()).isNull();
            assertThat(approval.getProtectedPayload()).isNull();
            assertThat(approval.getLeaseToken()).isNull();
            assertThat(approval.getLeaseUntil()).isNull();
            assertThat(approval.getReviewStartedAt()).isEqualTo(at);
            assertThat(approval.canPost(at.plusSeconds(3))).isFalse();
            assertThat(approval.claim(id(), at.plusSeconds(3), Duration.ofSeconds(60))).isFalse();
        }
    }

    @Test void paidPendingALostLeaseOrAReleasedHoldCannotClose() {
        PaymentApproval approval = held("PG_INVALID_EVIDENCE");
        String lease = id();
        approval.claimForClosure(lease, at.plusSeconds(1), Duration.ofSeconds(60));
        assertThatThrownBy(() -> approval.closeAfterReview(PENDING, lease, at.plusSeconds(2))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> approval.closeAfterReview(READY, id(), at.plusSeconds(2))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> approval.closeAfterReview(READY, lease, at.plusSeconds(61))).isInstanceOf(IllegalStateException.class);
        assertThat(approval.getStatus()).isEqualTo(PaymentApprovalStatus.REVIEW_REQUIRED);
        assertThat(approval.getProtectedPayload()).isEqualTo("encrypted-body");
    }

    @Test void theRecordKeepsTheOperatorEvidenceAndRejectsAChangedReplay() {
        PaymentApproval approval = held("PG_INVALID_EVIDENCE");
        ApprovalClosureRequest request = new ApprovalClosureRequest(id(), approval.getApprovalId(), "usr_operator", "PG console cancel 확인");
        ApprovalClosure closure = ApprovalClosure.record(approval, request, CANCELLED, at.plusSeconds(1), at.plusSeconds(1));
        assertThat(closure.view()).isEqualTo(new ApprovalClosureView(request.requestId(), approval.getApprovalId(), "usr_operator",
                "PG console cancel 확인", ApprovalObservation.State.CANCELLED, at.plusSeconds(1), at.plusSeconds(1)));
        assertThat(closure.reason()).hasSizeLessThanOrEqualTo(500).contains("CANCELLED");
        closure.requireSame(request);
        for (ApprovalClosureRequest changed : List.of(
                new ApprovalClosureRequest(request.requestId(), approval.getApprovalId(), "usr_other", request.note()),
                new ApprovalClosureRequest(request.requestId(), approval.getApprovalId(), request.operatorSubject(), "different"),
                new ApprovalClosureRequest(request.requestId(), id(), request.operatorSubject(), request.note())))
            assertThatThrownBy(() -> closure.requireSame(changed)).isInstanceOfSatisfying(BusinessException.class,
                    failure -> assertThat(failure.errorCode()).isEqualTo(PaymentErrorCode.APPROVAL_CLOSURE_CHANGED));
    }

    @Test void aRecordNeedsTheHeldApprovalClosableEvidenceAndOrderedTimes() {
        PaymentApproval approval = held("PG_INVALID_EVIDENCE");
        ApprovalClosureRequest request = new ApprovalClosureRequest(id(), approval.getApprovalId(), "usr_operator", "checked");
        assertThatThrownBy(() -> ApprovalClosure.record(approval, request, PENDING, at, at)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ApprovalClosure.record(approval, request, READY, at.plusSeconds(2), at.plusSeconds(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ApprovalClosure.record(approval, new ApprovalClosureRequest(id(), id(), "usr_operator", "checked"), READY, at, at))
                .isInstanceOf(IllegalArgumentException.class);
        PaymentApproval requested = approval();
        assertThatThrownBy(() -> ApprovalClosure.record(requested, new ApprovalClosureRequest(id(), requested.getApprovalId(), "usr_operator", "checked"), READY, at, at))
                .isInstanceOfSatisfying(BusinessException.class, failure -> assertThat(failure.errorCode()).isEqualTo(PaymentErrorCode.APPROVAL_NOT_HELD));
    }

    @Test void theCommandIsBoundedAndNeverBlank() {
        String approvalId = id();
        for (String note : new String[]{null, "", "   ", "x".repeat(501)})
            assertThatThrownBy(() -> new ApprovalClosureRequest(id(), approvalId, "usr_operator", note)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ApprovalClosureRequest("not-a-uuid", approvalId, "usr_operator", "note")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ApprovalClosureRequest(id(), approvalId, " ", "note")).isInstanceOf(IllegalArgumentException.class);
        assertThat(new ApprovalClosureRequest(id(), approvalId, "usr_operator", "x".repeat(500)).note()).hasSize(500);
    }

    @Test void aClosedPurchaseFailsWithTheReviewReasonAndNoFurtherLookups() {
        Payment purchase = Payment.receive(request(), "PORTONE_TEST");
        purchase.failAfterReview("tx-held", at, "Payment approval review closed with PG evidence READY", id(), at.plusSeconds(1));
        assertThat(purchase.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(purchase.getFailureReasonCode()).isEqualTo(ApprovalClosure.REASON_CODE);
        assertThat(purchase.getFailureReason()).contains("READY");
        assertThat(purchase.getProviderTransactionId()).isEqualTo("tx-held");
        assertThat(purchase.getProviderOccurredAt()).isEqualTo(at);
        assertThat(purchase.getResultEventId()).isNotNull();
        assertThat(purchase.getResultOccurredAt()).isEqualTo(at.plusSeconds(1));
        assertThat(purchase.getNextAttemptAt()).isNull();
        assertThat(purchase.getNextFailureLookupAt()).isNull();
        assertThat(purchase.getLastErrorCode()).isNull();
        assertThatThrownBy(purchase::expireUnpaid).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> purchase.failAfterReview("tx-held", at, "again", id(), at.plusSeconds(2))).isInstanceOf(BusinessException.class);
    }

    @Test void onlyAnUnresolvedPortOnePurchaseCanFailAfterReview() {
        Payment simulated = Payment.receive(request(), "SIMULATED");
        assertThatThrownBy(() -> simulated.failAfterReview("tx", at, "closed", id(), at)).isInstanceOf(BusinessException.class);
        Payment expired = Payment.receive(request(), "PORTONE_TEST"); expired.expireUnpaid();
        assertThatThrownBy(() -> expired.failAfterReview("tx", at, "closed", id(), at)).isInstanceOf(BusinessException.class);
        Payment pending = Payment.receive(request(), "PORTONE_TEST");
        assertThatThrownBy(() -> pending.failAfterReview("tx", at.plusSeconds(1), "closed", id(), at)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> pending.failAfterReview(" ", at, "closed", id(), at)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> pending.failAfterReview("tx", at, " ", id(), at)).isInstanceOf(IllegalArgumentException.class);
        assertThat(pending.getStatus()).isEqualTo(PaymentStatus.PENDING);
    }

    private PaymentApproval approval() {
        return PaymentApproval.receive(payment, new PaymentApprovalInput(id(), payment.getOrderNo(), "tx-" + id(), new ApprovalToken("secret-token")), "encrypted-body", at);
    }
    private PaymentApproval held(String reason) {
        PaymentApproval approval = approval();
        approval.stopPosts(reason, at);
        return approval;
    }
}
