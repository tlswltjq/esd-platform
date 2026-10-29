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
}
