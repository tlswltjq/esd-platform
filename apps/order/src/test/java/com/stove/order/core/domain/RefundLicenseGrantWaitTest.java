package com.stove.order.core.domain;

import static com.stove.order.OrderFixtures.id;
import static com.stove.order.OrderFixtures.order;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** 결정 125: 환불이 지급 확인(LicenseGranted)을 기다리기 시작한 시각과 10분 경계. */
class RefundLicenseGrantWaitTest {
    private static final Instant BASE = Instant.parse("2026-11-08T10:30:00.123456Z");
    private static final Duration THRESHOLD = Duration.ofMinutes(10);

    @Test void anAutomaticRefundStartsWaitingAtItsReceiptTime() {
        Refund refund = Refund.request(paid(BASE.minusSeconds(3600)), BASE);
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.PENDING_LICENSE_GRANT);
        assertThat(refund.getLicenseGrantWaitStartedAt()).isEqualTo(BASE);
    }

    @Test void aRefundWaitingForReviewHasNotStartedWaitingForTheGrant() {
        Refund refund = Refund.request(paid(BASE.minusSeconds(18000)), BASE);
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.PENDING_REVIEW);
        assertThat(refund.getLicenseGrantWaitStartedAt()).isNull();
        assertThat(refund.licenseGrantDelayedSince(BASE.plusSeconds(86400), THRESHOLD)).isNull();
    }

    @Test void anApprovedReviewStartsWaitingAtTheDecisionTimeAndARejectionDoesNot() {
        Refund approved = Refund.request(paid(BASE.minusSeconds(18000)), BASE);
        approved.decide(new RefundReviewRequest(id(), approved.getRefundId(), "admin", RefundReviewDecision.APPROVED, "승인"), BASE.plusSeconds(300));
        assertThat(approved.getLicenseGrantWaitStartedAt()).isEqualTo(BASE.plusSeconds(300));
        Refund rejected = Refund.request(paid(BASE.minusSeconds(18000)), BASE);
        rejected.decide(new RefundReviewRequest(id(), rejected.getRefundId(), "admin", RefundReviewDecision.REJECTED, "반려"), BASE.plusSeconds(300));
        assertThat(rejected.getLicenseGrantWaitStartedAt()).isNull();
    }

    @Test void anApprovedReconsiderationStartsTheWaitAgainAtItsDecisionTime() {
        Refund refund = Refund.request(paid(BASE.minusSeconds(18000)), BASE);
        RefundReviewRound first = RefundReviewRound.initial(refund, "usr_buyer");
        RefundReviewRequest reject = new RefundReviewRequest(id(), refund.getRefundId(), "admin", RefundReviewDecision.REJECTED, "반려");
        refund.decide(reject, BASE.plusSeconds(1)); first.decide(reject, BASE.plusSeconds(1));
        RefundReviewRound next = RefundReviewRound.reconsider(refund, first,
                new RefundReconsiderationRequest(id(), refund.getOrderNo(), first.getReviewId(), "usr_buyer", "재설치해도 실행 오류가 남았습니다"), BASE.plusSeconds(60));
        refund.openReconsideration(first, next);
        refund.decideReconsideration(next, new RefundReviewRequest(id(), refund.getRefundId(), "admin", RefundReviewDecision.APPROVED, "승인"), BASE.plusSeconds(900));
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.PENDING_LICENSE_GRANT);
        assertThat(refund.getLicenseGrantWaitStartedAt()).isEqualTo(BASE.plusSeconds(900));
    }

    @Test void theDelayStartsExactlyAtTheBoundaryAndReportsTheBoundaryTime() {
        Refund refund = Refund.request(paid(BASE.minusSeconds(3600)), BASE);
        Instant boundary = BASE.plus(THRESHOLD);
        assertThat(refund.licenseGrantDelayedSince(boundary.minusNanos(1000), THRESHOLD)).isNull();
        assertThat(refund.licenseGrantDelayedSince(boundary, THRESHOLD)).isEqualTo(boundary);
        assertThat(refund.licenseGrantDelayedSince(boundary.plusSeconds(86400), THRESHOLD)).isEqualTo(boundary);
    }

    @Test void theGrantEndsTheDelayButKeepsTheRecordedStartForOperators() {
        Order order = paid(BASE.minusSeconds(3600));
        Refund refund = Refund.request(order, BASE);
        refund.confirmLicenseGrant(new LicenseGrant(id(), BASE.plusSeconds(1200), id(), order.getOrderNo(), order.getBuyerSubject(),
                order.getProductId(), order.getProductCode(), order.getPaymentId()));
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.PENDING_LICENSE_SUSPENSION);
        assertThat(refund.getLicenseGrantWaitStartedAt()).isEqualTo(BASE);
        assertThat(refund.licenseGrantDelayedSince(BASE.plusSeconds(86400), THRESHOLD)).isNull();
    }

    private static Order paid(Instant resultAt) {
        Order paid = order();
        paid.apply(new PaymentResult(id(), resultAt, paid.getOrderNo(), paid.getRequestEventId(), id(),
                paid.getBuyerSubject(), paid.getAmount(), "KRW", OrderStatus.PAID, null, null));
        return paid;
    }
}
