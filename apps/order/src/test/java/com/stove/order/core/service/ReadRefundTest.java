package com.stove.order.core.service;

import static com.stove.order.OrderFixtures.order;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.stove.common.web.error.BusinessException;
import com.stove.order.core.domain.Order;
import com.stove.order.core.domain.OrderRepository;
import com.stove.order.core.domain.OrderStatus;
import com.stove.order.core.domain.Refund;
import com.stove.order.core.domain.RefundRepository;
import com.stove.order.core.domain.RefundStatus;
import com.stove.order.core.domain.RefundView;
import com.stove.order.core.domain.RefundAttempt;
import com.stove.order.core.domain.RefundAttemptRepository;
import com.stove.order.core.domain.RefundFailureReason;
import com.stove.order.core.domain.RefundReviewRoundRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

class ReadRefundTest {
    private final OrderRepository orders = mock(OrderRepository.class);
    private final RefundRepository refunds = mock(RefundRepository.class);
    private final RefundAttemptRepository attempts = mock(RefundAttemptRepository.class);
    private final ReadRefund read = new ReadRefund(orders, refunds, mock(RefundReviewRoundRepository.class), Clock.systemUTC(), attempts, Duration.ofMinutes(10));

    @Test void theOwnersSnapshotUsesTheOriginalOrderAmountAndStoredRefundFacts() {
        Order order = order();
        ReflectionTestUtils.setField(order, "status", OrderStatus.PAID);
        ReflectionTestUtils.setField(order, "resultOccurredAt", Instant.parse("2026-10-28T01:00:00Z"));
        Refund refund = Refund.request(order, Instant.parse("2026-10-28T02:00:00Z"));
        when(orders.findByOrderNoAndBuyerSubject(order.getOrderNo(), "usr_buyer")).thenReturn(Optional.of(order));
        when(refunds.findByOrderNo(order.getOrderNo())).thenReturn(Optional.of(refund));
        RefundView view = read.execute(order.getOrderNo(), "usr_buyer");
        assertThat(view.refundId()).isEqualTo(refund.getRefundId()); assertThat(view.amount()).isEqualTo(order.getAmount());
        assertThat(view.currency()).isEqualTo("KRW"); assertThat(view.status()).isEqualTo(RefundStatus.PENDING_LICENSE_GRANT);
        assertThat(view.receivedAt()).isEqualTo(refund.getReceivedAt()); assertThat(view.completedAt()).isNull();
    }

    @Test void missingOrUnownedOrdersDoNotRevealWhetherARefundExists() {
        when(orders.findByOrderNoAndBuyerSubject("unknown", "usr_buyer")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> read.execute("unknown", "usr_buyer")).isInstanceOf(BusinessException.class);
        verifyNoInteractions(refunds);
    }

    @Test void anOwnedOrderWithoutARefundIsNotFound() {
        Order order = order();
        when(orders.findByOrderNoAndBuyerSubject(order.getOrderNo(), "usr_buyer")).thenReturn(Optional.of(order));
        when(refunds.findByOrderNo(order.getOrderNo())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> read.execute(order.getOrderNo(), "usr_buyer")).isInstanceOf(BusinessException.class);
    }

    @ParameterizedTest @EnumSource(RefundStatus.class)
    void onlyTheCurrentRestoredFailureIsOfferedAsAnExplicitRetryTarget(RefundStatus status) {
        Order order = order();
        ReflectionTestUtils.setField(order, "status", OrderStatus.PAID);
        ReflectionTestUtils.setField(order, "resultOccurredAt", Instant.parse("2026-10-28T01:00:00Z"));
        Refund refund = Refund.request(order, Instant.parse("2026-10-28T02:00:00Z"));
        RefundAttempt failed = failedAttempt(refund, RefundAttempt.PG_CANCELLATION_FAILED);
        String attempt = failed.getAttemptId();
        when(attempts.findByAttemptId(attempt)).thenReturn(Optional.of(failed));
        ReflectionTestUtils.setField(refund, "status", status);
        ReflectionTestUtils.setField(refund, "currentAttemptId", attempt);
        when(orders.findByOrderNoAndBuyerSubject(order.getOrderNo(), "usr_buyer")).thenReturn(Optional.of(order));
        when(refunds.findByOrderNo(order.getOrderNo())).thenReturn(Optional.of(refund));
        assertThat(read.execute(order.getOrderNo(), "usr_buyer").retryableAttemptId()).isEqualTo(status == RefundStatus.FAILED ? attempt : null);
    }

    @ParameterizedTest @EnumSource(RefundStatus.class)
    void onlyARestoredFailureCarriesTheCustomerFailureReasonMovedFromTheStoredAttemptCode(RefundStatus status) {
        Order order = paidOrder();
        Refund refund = refund(order);
        RefundAttempt attempt = failedAttempt(refund, RefundAttempt.PG_CANCELLATION_FAILED);
        ReflectionTestUtils.setField(refund, "status", status);
        ReflectionTestUtils.setField(refund, "currentAttemptId", attempt.getAttemptId());
        when(attempts.findByAttemptId(attempt.getAttemptId())).thenReturn(Optional.of(attempt));
        assertThat(read.execute(order.getOrderNo(), "usr_buyer").failureReason())
                .isEqualTo(status == RefundStatus.FAILED ? RefundFailureReason.PAYMENT_NOT_CANCELLED : null);
    }

    @Test void aFailedRefundWhoseAttemptOrStoredCodeCannotBeExplainedIsAConflictNotAGuess() {
        Order order = paidOrder();
        Refund refund = refund(order);
        ReflectionTestUtils.setField(refund, "status", RefundStatus.FAILED);
        RefundAttempt unknown = failedAttempt(refund, "SOME_NEW_INTERNAL_CODE");
        ReflectionTestUtils.setField(refund, "currentAttemptId", unknown.getAttemptId());
        when(attempts.findByAttemptId(unknown.getAttemptId())).thenReturn(Optional.of(unknown));
        assertThatThrownBy(() -> read.execute(order.getOrderNo(), "usr_buyer")).isInstanceOf(BusinessException.class);
        ReflectionTestUtils.setField(refund, "currentAttemptId", "33333333-3333-3333-3333-333333333333");
        assertThatThrownBy(() -> read.execute(order.getOrderNo(), "usr_buyer")).isInstanceOf(BusinessException.class);
    }

    private static Order paidOrder() {
        Order order = order();
        ReflectionTestUtils.setField(order, "status", OrderStatus.PAID);
        ReflectionTestUtils.setField(order, "resultOccurredAt", Instant.parse("2026-10-28T01:00:00Z"));
        return order;
    }

    private Refund refund(Order order) {
        Refund refund = Refund.request(order, Instant.parse("2026-10-28T02:00:00Z"));
        when(orders.findByOrderNoAndBuyerSubject(order.getOrderNo(), "usr_buyer")).thenReturn(Optional.of(order));
        when(refunds.findByOrderNo(order.getOrderNo())).thenReturn(Optional.of(refund));
        return refund;
    }

    private static RefundAttempt failedAttempt(Refund refund, String code) {
        RefundAttempt attempt = RefundAttempt.start(refund.getRefundId(), 1, Instant.parse("2026-10-28T02:01:00Z"));
        attempt.fail(Instant.parse("2026-10-28T02:02:00Z"), code, "PG 내부 오류 원문");
        return attempt;
    }
}
