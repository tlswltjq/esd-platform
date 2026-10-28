package com.stove.order.core.service;

import static com.stove.order.OrderFixtures.id;
import static com.stove.order.OrderFixtures.order;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.stove.common.web.error.BusinessException;
import com.stove.order.core.domain.Order;
import com.stove.order.core.domain.OrderRepository;
import com.stove.order.core.domain.OrderStatus;
import com.stove.order.core.domain.PaymentResult;
import com.stove.order.core.domain.Refund;
import com.stove.order.core.domain.RefundApprovalStopRequest;
import com.stove.order.core.domain.RefundRepository;
import com.stove.order.core.domain.RefundReviewDecision;
import com.stove.order.core.domain.RefundReviewRequest;
import com.stove.order.core.domain.RefundReviewRound;
import com.stove.order.core.domain.RefundReviewRoundRepository;
import com.stove.order.core.domain.RefundStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class RequestRefundApprovalStopTest {
    private static final Instant AT = Instant.parse("2026-10-29T04:00:00.123456Z");
    private final OrderRepository orders = mock(OrderRepository.class);
    private final RefundRepository refunds = mock(RefundRepository.class);
    private final RefundReviewRoundRepository rounds = mock(RefundReviewRoundRepository.class);
    private final RequestRefundApprovalStop stop = new RequestRefundApprovalStop(orders, refunds, rounds, Clock.fixed(AT.plusSeconds(1), ZoneOffset.UTC));

    @Test void beforeSuspensionAnApprovalStopOpensANewReviewWithoutRewritingTheApproval() {
        Refund refund = approved(); String original = refund.getCurrentReviewId();
        RefundApprovalStopRequest input = input(refund);
        assertThat(stop.execute(input).requestId()).isEqualTo(input.requestId());
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.PENDING_REVIEW);
        assertThat(refund.getCurrentReviewId()).isNotEqualTo(original);
        assertThat(refund.reviewReceipt().decision()).isEqualTo(RefundReviewDecision.APPROVED);
        assertThat(refund.getSuspensionRequestEventId()).isNull();
    }

    @Test void anOutstandingSuspensionMustBeRestoredBeforeAnotherReviewCanStart() {
        Refund refund = approved(); ReflectionTestUtils.setField(refund, "status", RefundStatus.PENDING_LICENSE_SUSPENSION);
        ReflectionTestUtils.setField(refund, "suspensionRequestEventId", id()); String original = refund.getCurrentReviewId();
        stop.execute(input(refund));
        assertThat(refund.getStatus().name()).isEqualTo("PENDING_APPROVAL_STOP");
        assertThat(refund.getCurrentReviewId()).isEqualTo(original);
    }

    @Test void aStoredPaymentExecutionRequestClosesTheApprovalStopWindow() {
        Refund refund = approved(); ReflectionTestUtils.setField(refund, "status", RefundStatus.PENDING_PAYMENT_REFUND);
        ReflectionTestUtils.setField(refund, "currentAttemptId", id());
        assertThatThrownBy(() -> stop.execute(input(refund))).isInstanceOf(BusinessException.class);
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.PENDING_PAYMENT_REFUND);
    }

    private RefundApprovalStopRequest input(Refund refund) { return new RefundApprovalStopRequest(id(), refund.getRefundId(), refund.getCurrentReviewId(), "admin", "자료를 잘못 읽어 재검토가 필요합니다"); }
    private Refund approved() {
        Order paid = order(); ReflectionTestUtils.setField(paid, "createdAt", AT.minusSeconds(21600));
        paid.apply(new PaymentResult(id(), AT.minusSeconds(18000), paid.getOrderNo(), paid.getRequestEventId(), id(), paid.getBuyerSubject(), paid.getAmount(), paid.getCurrency(), OrderStatus.PAID, null, null));
        Refund refund = Refund.request(paid, AT); RefundReviewRound first = RefundReviewRound.initial(refund, paid.getBuyerSubject());
        RefundReviewRequest decision = new RefundReviewRequest(id(), refund.getRefundId(), "admin", RefundReviewDecision.APPROVED, "승인");
        refund.decide(decision, AT); first.decide(decision, AT);
        when(refunds.findOrderNoByRefundId(refund.getRefundId())).thenReturn(Optional.of(paid.getOrderNo()));
        when(orders.lockByOrderNo(paid.getOrderNo())).thenReturn(Optional.of(paid));
        when(refunds.findByRefundId(refund.getRefundId())).thenReturn(Optional.of(refund));
        when(rounds.findByReviewId(first.getReviewId())).thenReturn(Optional.of(first));
        return refund;
    }
}
