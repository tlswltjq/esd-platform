package com.stove.order.core.domain;

import static com.stove.order.OrderFixtures.id;
import static com.stove.order.OrderFixtures.order;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 결정 125·129 범위 확장: 정지·복구·승인 중단 대기에 들어간 시각과 10분 경계.
 * 진입 경로는 지급 확인(자동 접수·심사 승인·재심사 승인 포함)·재시도·확정 실패·승인 중단이다.
 */
class RefundLicenseStepWaitTest {
    private static final Instant BASE = Instant.parse("2026-11-08T10:30:00.123456Z");
    private static final Duration THRESHOLD = Duration.ofMinutes(10);

    @Test void theGrantConfirmationStartsTheSuspensionWaitAtTheGivenTime() {
        Fixture f = automatic();
        Instant confirmed = BASE.plusSeconds(30);
        assertThat(f.refund().confirmLicenseGrant(f.grant(), confirmed)).isTrue();
        assertThat(f.refund().getStatus()).isEqualTo(RefundStatus.PENDING_LICENSE_SUSPENSION);
        assertThat(f.refund().getLicenseSuspensionWaitStartedAt()).isEqualTo(confirmed);
        assertThat(f.refund().confirmLicenseGrant(f.grant(), confirmed.plusSeconds(60))).as("다시 받은 지급 확인은 진입 시각을 바꾸지 않는다").isFalse();
        assertThat(f.refund().getLicenseSuspensionWaitStartedAt()).isEqualTo(confirmed);
    }

    @Test void anApprovalWithAnExistingGrantStartsTheSuspensionWaitAtTheDecisionTime() {
        Order paid = paid(BASE.minusSeconds(18000));
        Refund refund = Refund.request(paid, BASE);
        Instant decided = BASE.plusSeconds(300);
        refund.decide(new RefundReviewRequest(id(), refund.getRefundId(), "admin", RefundReviewDecision.APPROVED, "승인"), decided);
        refund.confirmLicenseGrant(grant(paid), decided);
        assertThat(refund.getLicenseGrantWaitStartedAt()).isEqualTo(decided);
        assertThat(refund.getLicenseSuspensionWaitStartedAt()).isEqualTo(decided);
    }

    @Test void aRetryStartsTheSuspensionWaitAgainAtTheRetryTime() {
        Fixture f = restored();
        Instant retried = BASE.plusSeconds(7200);
        RefundAttempt previous = f.attempt();
        assertThat(f.refund().requestRetry(f.order(), previous, retried)).isTrue();
        assertThat(f.refund().getStatus()).isEqualTo(RefundStatus.PENDING_LICENSE_SUSPENSION);
        assertThat(f.refund().getLicenseSuspensionWaitStartedAt()).isEqualTo(retried);
    }

    @Test void aConfirmedFailureStartsTheRestorationWaitAtTheRecordingTime() {
        Fixture f = executing();
        Instant failedAt = BASE.plusSeconds(120);
        assertThat(f.refund().recordFailure(f.order(), f.attempt(), failure(f), failedAt)).isTrue();
        assertThat(f.refund().getStatus()).isEqualTo(RefundStatus.PENDING_LICENSE_RESTORATION);
        assertThat(f.refund().getLicenseRestorationWaitStartedAt()).isEqualTo(failedAt);
    }

    @Test void anApprovalStopStartsTheStopWaitAtTheStopTime() {
        Order paid = paid(BASE.minusSeconds(18000));
        Refund refund = Refund.request(paid, BASE);
        RefundReviewRound round = RefundReviewRound.initial(refund, paid.getBuyerSubject());
        RefundReviewRequest approve = new RefundReviewRequest(id(), refund.getRefundId(), "admin", RefundReviewDecision.APPROVED, "승인");
        refund.decide(approve, BASE.plusSeconds(1)); round.decide(approve, BASE.plusSeconds(1));
        refund.confirmLicenseGrant(grant(paid), BASE.plusSeconds(1));
        Instant stopped = BASE.plusSeconds(60);
        refund.stopApproval(round, new RefundApprovalStopRequest(id(), refund.getRefundId(), round.getReviewId(), "admin", "잘못 승인"), stopped);
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.PENDING_APPROVAL_STOP);
        assertThat(refund.getApprovalStopWaitStartedAt()).isEqualTo(stopped);
    }

    @Test void eachWaitStateReportsItsBoundaryFromItsOwnStartAndOnlyWhileInThatState() {
        Refund refund = automatic().refund();
        ReflectionTestUtils.setField(refund, "licenseGrantWaitStartedAt", BASE.minusSeconds(86400));
        ReflectionTestUtils.setField(refund, "licenseSuspensionWaitStartedAt", BASE);
        ReflectionTestUtils.setField(refund, "licenseRestorationWaitStartedAt", BASE.plusSeconds(1));
        ReflectionTestUtils.setField(refund, "approvalStopWaitStartedAt", BASE.plusSeconds(2));
        assertBoundary(refund, RefundStatus.PENDING_LICENSE_GRANT, BASE.minusSeconds(86400));
        assertBoundary(refund, RefundStatus.PENDING_LICENSE_SUSPENSION, BASE);
        assertBoundary(refund, RefundStatus.PENDING_LICENSE_RESTORATION, BASE.plusSeconds(1));
        assertBoundary(refund, RefundStatus.PENDING_APPROVAL_STOP, BASE.plusSeconds(2));
        for (RefundStatus other : new RefundStatus[] {RefundStatus.PENDING_REVIEW, RefundStatus.PENDING_PAYMENT_REFUND,
                RefundStatus.FAILED, RefundStatus.REJECTED, RefundStatus.COMPLETED}) {
            ReflectionTestUtils.setField(refund, "status", other);
            assertThat(refund.licenseWaitDelayedSince(BASE.plusSeconds(86400), THRESHOLD)).as(other.name()).isNull();
        }
    }

    @Test void leavingTheSuspensionWaitEndsTheDelayButKeepsTheStartForOperators() {
        Fixture f = executing();
        assertThat(f.refund().getStatus()).isEqualTo(RefundStatus.PENDING_PAYMENT_REFUND);
        assertThat(f.refund().getLicenseSuspensionWaitStartedAt()).isEqualTo(BASE);
        assertThat(f.refund().licenseWaitDelayedSince(BASE.plusSeconds(86400), THRESHOLD)).isNull();
    }

    @Test void aWaitWithoutARecordedStartIsNotReported() {
        Refund refund = automatic().refund();
        ReflectionTestUtils.setField(refund, "status", RefundStatus.PENDING_LICENSE_RESTORATION);
        assertThat(refund.licenseWaitDelayedSince(BASE.plusSeconds(86400), THRESHOLD)).isNull();
    }

    private static void assertBoundary(Refund refund, RefundStatus status, Instant start) {
        ReflectionTestUtils.setField(refund, "status", status);
        Instant boundary = start.plus(THRESHOLD);
        assertThat(refund.licenseWaitDelayedSince(boundary.minusNanos(1000), THRESHOLD)).as(status.name()).isNull();
        assertThat(refund.licenseWaitDelayedSince(boundary, THRESHOLD)).as(status.name()).isEqualTo(boundary);
    }

    private static Fixture automatic() {
        Order paid = paid(BASE.minusSeconds(3600));
        return new Fixture(paid, Refund.request(paid, BASE), grant(paid), null);
    }

    private static Fixture executing() {
        Fixture f = automatic();
        f.refund().confirmLicenseGrant(f.grant(), BASE);
        LicenseSuspensionConfirmation suspended = new LicenseSuspensionConfirmation(id(), BASE.plusSeconds(10),
                f.refund().getSuspensionRequestEventId(), f.refund().getRefundId(), f.grant().licenseId(), f.order().getOrderNo(),
                f.order().getBuyerSubject(), f.order().getProductId(), f.order().getProductCode(), f.order().getPaymentId());
        RefundAttempt attempt = RefundAttempt.afterSuspension(f.refund(), suspended, BASE.plusSeconds(11));
        f.refund().recordExecution(attempt);
        return new Fixture(f.order(), f.refund(), f.grant(), attempt);
    }

    private static Fixture restored() {
        Fixture f = executing();
        RefundFailureConfirmation failure = failure(f);
        f.refund().recordFailure(f.order(), f.attempt(), failure, BASE.plusSeconds(120));
        f.refund().confirmRestoration(f.order(), f.attempt(), new LicenseRestorationConfirmation(id(), BASE.plusSeconds(130),
                f.attempt().getRestorationRequestEventId(), f.refund().getRefundId(), f.attempt().getAttemptId(), f.attempt().getFailureEventId(),
                f.attempt().getLicenseId(), f.order().getOrderNo(), f.order().getBuyerSubject(), f.order().getProductId(), f.order().getProductCode(),
                f.order().getPaymentId(), f.attempt().getSuspensionRequestEventId(), f.attempt().getSuspensionEventId(), false));
        return f;
    }

    private static RefundFailureConfirmation failure(Fixture f) {
        return new RefundFailureConfirmation(id(), BASE.plusSeconds(100), f.refund().getRefundId(), f.attempt().getAttemptId(),
                f.attempt().getExecutionRequestEventId(), f.order().getOrderNo(), f.order().getPaymentId(), f.order().getBuyerSubject(),
                f.order().getAmount(), f.order().getCurrency(), "pg-original", "cancel-own", "a".repeat(64));
    }

    private static LicenseGrant grant(Order paid) {
        return new LicenseGrant(id(), BASE.minusSeconds(60), id(), paid.getOrderNo(), paid.getBuyerSubject(),
                paid.getProductId(), paid.getProductCode(), paid.getPaymentId());
    }

    private static Order paid(Instant resultAt) {
        Order paid = order();
        paid.apply(new PaymentResult(id(), resultAt, paid.getOrderNo(), paid.getRequestEventId(), id(),
                paid.getBuyerSubject(), paid.getAmount(), "KRW", OrderStatus.PAID, null, null));
        return paid;
    }

    private record Fixture(Order order, Refund refund, LicenseGrant grant, RefundAttempt attempt) { }
}
