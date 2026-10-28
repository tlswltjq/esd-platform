package com.stove.order.core.domain;

import static com.stove.order.OrderFixtures.id;
import static com.stove.order.OrderFixtures.order;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stove.common.web.error.BusinessException;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class RefundReconsiderationTest {
    private static final Instant BASE = Instant.parse("2026-10-28T12:00:00.123456Z");

    @Test void aNewRoundPreservesTheOriginalRefundEligibilityAndFirstRejection() {
        Fixture fixture = rejected(); RefundReviewReceipt first = fixture.refund().reviewReceipt();
        RefundReviewRound next = next(fixture, fixture.first(), BASE.plusSeconds(10));
        fixture.refund().openReconsideration(fixture.first(), next);
        assertThat(next.getReviewNo()).isEqualTo(2); assertThat(next.getPreviousReviewId()).isEqualTo(fixture.first().getReviewId());
        assertThat(fixture.refund().getStatus()).isEqualTo(RefundStatus.PENDING_REVIEW);
        assertThat(fixture.refund().getReceivedAt()).isEqualTo(BASE); assertThat(fixture.refund().isAutomaticRefundEligible()).isFalse();
        assertThat(fixture.refund().reviewReceipt()).isEqualTo(first); assertThat(fixture.refund().getSuspensionRequestEventId()).isNull();
    }

    @Test void exactlySevenDaysIsAcceptedAndOneMicrosecondLaterIsRejected() {
        Fixture fixture = rejected(); Instant deadline = BASE.plusSeconds(7 * 86400);
        assertThat(fixture.first().canReconsiderAt(deadline)).isTrue(); assertThat(next(fixture, fixture.first(), deadline).getReviewNo()).isEqualTo(2);
        assertThatThrownBy(() -> next(fixture, fixture.first(), deadline.plusNanos(1000))).isInstanceOf(BusinessException.class);
        assertThat(fixture.first().canReconsiderAt(BASE.minusNanos(1000))).isFalse();
    }

    @Test void repeatedRejectionsCanProduceMoreThanOneReconsiderationAndOldDecisionsRemainImmutable() {
        Fixture fixture = rejected(); RefundReviewReceipt first = fixture.first().receipt(); RefundReviewRound previous = fixture.first();
        for (int index = 2; index <= 5; index++) {
            Instant at = BASE.plusSeconds(index * 86400L);
            RefundReviewRound next = next(fixture, previous, at); fixture.refund().openReconsideration(previous, next);
            RefundReviewRequest reject = decision(fixture.refund(), RefundReviewDecision.REJECTED);
            assertThat(fixture.refund().decideReconsideration(next, reject, at.plusSeconds(1))).isTrue();
            assertThat(next.getReviewNo()).isEqualTo(index); assertThat(next.getDecisionReason()).isEqualTo("재심사 판정 사유");
            assertThat(fixture.refund().decide(new RefundReviewRequest(first.requestId(), first.refundId(), first.operatorSubject(), first.decision(), first.reason()), at)).isFalse();
            assertThat(fixture.refund().getCurrentReviewId()).isEqualTo(next.getReviewId()); previous = next;
        }
        assertThat(fixture.refund().reviewReceipt()).isEqualTo(first);
    }

    @Test void anOldRoundDecisionReplayDoesNotChangeTheCurrentPendingRound() {
        Fixture fixture = rejected(); RefundReviewRound second = next(fixture, fixture.first(), BASE.plusSeconds(1));
        fixture.refund().openReconsideration(fixture.first(), second); RefundReviewRequest rejected = decision(fixture.refund(), RefundReviewDecision.REJECTED);
        fixture.refund().decideReconsideration(second, rejected, BASE.plusSeconds(2));
        RefundReviewRound third = next(fixture, second, BASE.plusSeconds(3)); fixture.refund().openReconsideration(second, third);
        assertThat(fixture.refund().decideReconsideration(second, rejected, BASE.plusSeconds(4))).isFalse();
        assertThat(fixture.refund().getStatus()).isEqualTo(RefundStatus.PENDING_REVIEW); assertThat(fixture.refund().getCurrentReviewId()).isEqualTo(third.getReviewId());
        assertThatThrownBy(() -> fixture.refund().decideReconsideration(second, decision(fixture.refund(), RefundReviewDecision.APPROVED), BASE.plusSeconds(4)))
                .isInstanceOf(BusinessException.class);
    }

    @Test void approvalWaitsForGrantAndFurtherAppealsCannotUseTheApprovedRound() {
        Fixture fixture = rejected(); RefundReviewRound next = next(fixture, fixture.first(), BASE.plusSeconds(1));
        fixture.refund().openReconsideration(fixture.first(), next);
        fixture.refund().decideReconsideration(next, decision(fixture.refund(), RefundReviewDecision.APPROVED), BASE.plusSeconds(2));
        assertThat(fixture.refund().getStatus()).isEqualTo(RefundStatus.PENDING_LICENSE_GRANT);
        assertThat(next.canReconsiderAt(BASE.plusSeconds(3))).isFalse(); assertThat(fixture.refund().getSuspensionRequestEventId()).isNull();
    }

    @Test void mandatoryReasonAndIdentityValuesAreCheckedWhenTheInputIsCreated() {
        for (String reason : new String[]{null, "", " ", "x".repeat(501)})
            assertThatThrownBy(() -> new RefundReconsiderationRequest(id(), id(), id(), "buyer", reason)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> new RefundReconsiderationRequest("invalid", id(), id(), "buyer", "reason")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> new RefundRoundDecisionRequest("invalid", decision(rejected().refund(), RefundReviewDecision.APPROVED))).isInstanceOf(BusinessException.class);
    }

    @Test void aReceiptReplayRequiresTheSameBuyerPredecessorReasonAndCommand() {
        Fixture fixture = rejected(); RefundReconsiderationRequest input = new RefundReconsiderationRequest(id(), fixture.refund().getOrderNo(), fixture.first().getReviewId(), "usr_buyer", "고객의 설명");
        RefundReviewRound round = RefundReviewRound.reconsider(fixture.refund(), fixture.first(), input, BASE.plusSeconds(1));
        round.requireSameApplication(fixture.refund(), input);
        assertThatThrownBy(() -> round.requireSameApplication(fixture.refund(), new RefundReconsiderationRequest(input.requestId(), input.orderNo(), input.previousReviewId(), input.buyerSubject(), "다른 설명")))
                .isInstanceOf(BusinessException.class);
        assertThat(round.applicationReceipt().requestId()).isEqualTo(input.requestId());
    }

    private RefundReviewRound next(Fixture fixture, RefundReviewRound previous, Instant at) {
        return RefundReviewRound.reconsider(fixture.refund(), previous,
                new RefundReconsiderationRequest(id(), fixture.refund().getOrderNo(), previous.getReviewId(), "usr_buyer", "재설치해도 실행 오류가 남았습니다"), at);
    }
    private RefundReviewRequest decision(Refund refund, RefundReviewDecision decision) { return new RefundReviewRequest(id(), refund.getRefundId(), "admin", decision, "재심사 판정 사유"); }
    private Fixture rejected() {
        Order paid = order(); paid.apply(new PaymentResult(id(), BASE.minusSeconds(18000), paid.getOrderNo(), paid.getRequestEventId(), id(),
                paid.getBuyerSubject(), paid.getAmount(), "KRW", OrderStatus.PAID, null, null));
        Refund refund = Refund.request(paid, BASE); RefundReviewRound first = RefundReviewRound.initial(refund, "usr_buyer");
        RefundReviewRequest reject = decision(refund, RefundReviewDecision.REJECTED); refund.decide(reject, BASE); first.decide(reject, BASE);
        return new Fixture(refund, first);
    }
    private record Fixture(Refund refund, RefundReviewRound first) { }
}
