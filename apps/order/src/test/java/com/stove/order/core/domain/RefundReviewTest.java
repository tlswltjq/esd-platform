package com.stove.order.core.domain;

import static com.stove.order.OrderFixtures.id;
import static com.stove.order.OrderFixtures.order;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class RefundReviewTest {
    private static final Instant BASE = Instant.parse("2026-10-28T10:00:00.123456Z");

    @Test void approvalRecordsTheFirstDecisionAndWaitsForActualLicenseGrant() {
        Refund refund = pending(); RefundReviewRequest input = request(refund, RefundReviewDecision.APPROVED);
        assertThat(refund.decide(input, BASE.plusSeconds(1))).isTrue();
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.PENDING_LICENSE_GRANT);
        assertThat(refund.getSuspensionRequestEventId()).isNull();
        assertThat(refund.reviewReceipt()).isEqualTo(new RefundReviewReceipt(input.requestId(), refund.getRefundId(), input.operatorSubject(), input.decision(), input.reason(), BASE.plusSeconds(1)));
        assertThat(refund.getReceivedAt()).isEqualTo(BASE); assertThat(refund.isAutomaticRefundEligible()).isFalse();
    }

    @Test void rejectionIsDistinctFromPgFailureAndDoesNotAuthorizeSuspension() {
        Refund refund = pending(); RefundReviewRequest input = request(refund, RefundReviewDecision.REJECTED);
        assertThat(refund.decide(input, BASE.plusSeconds(1))).isTrue();
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.REJECTED);
        assertThat(refund.getSuspensionRequestEventId()).isNull(); assertThat(refund.getCurrentAttemptId()).isNull();
        assertThat(refund.getCompletedAt()).isNull();
        assertThat(refund.reviewReceipt().decision()).isEqualTo(RefundReviewDecision.REJECTED);
    }

    @Test void anExactReplayKeepsTheFirstReceiptAfterProcessingHasAdvanced() {
        Refund refund = pending(); RefundReviewRequest input = request(refund, RefundReviewDecision.APPROVED);
        refund.decide(input, BASE.plusSeconds(1)); RefundReviewReceipt original = refund.reviewReceipt();
        ReflectionTestUtils.setField(refund, "status", RefundStatus.COMPLETED);
        assertThat(refund.decide(input, BASE.plusSeconds(60))).isFalse();
        assertThat(refund.reviewReceipt()).isEqualTo(original);
    }

    @Test void anotherDecisionNewCommandOrChangedPayloadCannotRewriteTheFirstDecision() {
        Refund refund = pending(); RefundReviewRequest input = request(refund, RefundReviewDecision.APPROVED);
        refund.decide(input, BASE.plusSeconds(1)); RefundReviewReceipt first = refund.reviewReceipt();
        for (RefundReviewRequest other : new RefundReviewRequest[]{
                new RefundReviewRequest(id(), input.refundId(), input.operatorSubject(), input.decision(), input.reason()),
                new RefundReviewRequest(input.requestId(), input.refundId(), "another-admin", input.decision(), input.reason()),
                new RefundReviewRequest(input.requestId(), input.refundId(), input.operatorSubject(), RefundReviewDecision.REJECTED, input.reason()),
                new RefundReviewRequest(input.requestId(), input.refundId(), input.operatorSubject(), input.decision(), "changed reason")}) {
            assertThatThrownBy(() -> refund.decide(other, BASE.plusSeconds(2))).isInstanceOfSatisfying(BusinessException.class,
                    failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.CONFLICT));
        }
        assertThat(refund.reviewReceipt()).isEqualTo(first);
    }

    @Test void anAutomaticRefundOrAnotherRefundIdentityCannotBeReviewed() {
        Order paid = paid(); Refund automatic = Refund.request(paid, BASE.minusSeconds(7200));
        assertThatThrownBy(() -> automatic.decide(request(automatic, RefundReviewDecision.REJECTED), BASE))
                .isInstanceOf(BusinessException.class);
        Refund pending = pending();
        assertThatThrownBy(() -> pending.decide(new RefundReviewRequest(id(), id(), "admin", RefundReviewDecision.APPROVED, "reviewed"), BASE))
                .isInstanceOf(BusinessException.class);
    }

    @Test void inputAndDecisionTimeAreValidatedBeforeChangingTheAggregate() {
        Refund pending = pending();
        assertThatThrownBy(() -> new RefundReviewRequest("bad", pending.getRefundId(), "admin", RefundReviewDecision.APPROVED, "reason")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> new RefundReviewRequest(id(), pending.getRefundId(), " ", RefundReviewDecision.APPROVED, "reason")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> new RefundReviewRequest(id(), pending.getRefundId(), "admin", RefundReviewDecision.REJECTED, " ")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> pending.decide(request(pending, RefundReviewDecision.APPROVED), BASE.minusSeconds(1))).isInstanceOf(BusinessException.class);
        assertThat(pending.reviewReceipt()).isNull(); assertThat(pending.getStatus()).isEqualTo(RefundStatus.PENDING_REVIEW);
    }

    private RefundReviewRequest request(Refund refund, RefundReviewDecision decision) {
        return new RefundReviewRequest(id(), refund.getRefundId(), "admin", decision, "확인한 판정 사유");
    }
    private Refund pending() { return Refund.request(paid(), BASE); }
    private Order paid() {
        Order order = order(); ReflectionTestUtils.setField(order, "createdAt", BASE.minusSeconds(21600));
        order.apply(new PaymentResult(id(), BASE.minusSeconds(18000), order.getOrderNo(), order.getRequestEventId(), id(),
                order.getBuyerSubject(), order.getAmount(), order.getCurrency(), OrderStatus.PAID, null, null));
        return order;
    }
}
