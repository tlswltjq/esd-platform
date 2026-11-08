package com.stove.order.core.service;

import static com.stove.order.OrderFixtures.envelope;
import static com.stove.order.OrderFixtures.id;
import static com.stove.order.OrderFixtures.order;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.stove.common.kafka.contract.payload.OrderPaymentCompletedEvent;
import com.stove.common.kafka.contract.payload.RefundAmountsSnapshot;
import com.stove.common.kafka.contract.payload.RefundCancellationAmount;
import com.stove.common.kafka.contract.payload.RefundPaymentConfirmedEvent;
import com.stove.order.api.controller.dto.AdminRefundResponse;
import com.stove.order.api.controller.dto.RefundAmountDetailsResponse;
import com.stove.order.api.controller.dto.RefundResponse;
import com.stove.order.api.listener.PaymentResultMapper;
import com.stove.order.api.listener.RefundPaymentConfirmationMapper;
import com.stove.order.core.domain.LicenseGrant;
import com.stove.order.core.domain.LicenseSuspensionConfirmation;
import com.stove.order.core.domain.Order;
import com.stove.order.core.domain.OrderRepository;
import com.stove.order.core.domain.OrderStatus;
import com.stove.order.core.domain.PaymentResult;
import com.stove.order.core.domain.ProviderPaymentAmounts;
import com.stove.order.core.domain.Refund;
import com.stove.order.core.domain.RefundAmountView;
import com.stove.order.core.domain.RefundAttempt;
import com.stove.order.core.domain.RefundAttemptRepository;
import com.stove.order.core.domain.RefundCompletionRejected;
import com.stove.order.core.domain.RefundPaymentConfirmation;
import com.stove.order.core.domain.RefundExecutionSuccess;
import com.stove.order.core.domain.RefundAttemptStatus;
import com.stove.order.core.domain.RefundRepository;
import com.stove.order.core.domain.RefundReviewRoundRepository;
import com.stove.order.core.domain.RefundStatus;
import com.stove.order.core.domain.ReportedRefundAmounts;
import com.stove.order.core.domain.ReportedRefundCancellation;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RefundAmountDetailsTest {
    private static final Instant PAID_AT = Instant.parse("2026-10-30T01:00:00Z");
    private final OrderRepository orders = mock(OrderRepository.class);
    private final RefundRepository refunds = mock(RefundRepository.class);
    private final RefundReviewRoundRepository rounds = mock(RefundReviewRoundRepository.class);

    @Test void theApprovalMapperRetainsTheProviderBreakdownInsteadOfDroppingIt() {
        Order order = order();
        OrderPaymentCompletedEvent event = new OrderPaymentCompletedEvent(id(), PAID_AT, order.getOrderNo(),
                order.getRequestEventId(), id(), order.getBuyerSubject(), 10000, "KRW", 9976L, 24L);
        assertThat(PaymentResultMapper.from(envelope(event), event).paymentAmounts()).isEqualTo(new ProviderPaymentAmounts(9976, 24));
    }

    @Test void aPendingRefundExposesApprovalAmountsWithoutInventingReturnAmounts() {
        Order order = paid(); Refund refund = refund(order);
        RefundResponse response = customer(order, refund);
        assertThat(response.amount()).isEqualTo(10000);
        assertThat(response.amountDetails().pgPaidAmount()).isEqualTo(9976L);
        assertThat(response.amountDetails().pgDiscountAmount()).isEqualTo(24L);
        assertThat(response.amountDetails().confirmedCancellationAmount()).isNull();
        assertThat(response.amountDetails().confirmedPaymentMethodRefundAmount()).isNull();
        assertThat(response.amountDetails().unconfirmedFields()).contains("confirmedCancellationAmount", "confirmedPaymentMethodRefundAmount");
    }

    @Test void customerAndAdminSeeTheSameConfirmedCancellationAndPointsWithTheOriginalAmount() {
        Order order = paid(); Refund refund = refund(order); RefundAttempt attempt = start(order, refund);
        PaymentResult original = order.paymentResult();
        refund.confirmPayment(order, attempt, confirmation(order, refund, attempt, 12L));
        RefundResponse customer = customer(order, refund);
        when(orders.findByOrderNo(order.getOrderNo())).thenReturn(Optional.of(order));
        when(refunds.findByRefundId(refund.getRefundId())).thenReturn(Optional.of(refund));
        AdminRefundResponse admin = AdminRefundResponse.from(new ReadRefundReviews(orders, refunds, rounds).read(refund.getRefundId()));
        assertThat(customer.amount()).isEqualTo(10000);
        assertThat(customer.status()).isEqualTo(RefundResponse.Status.COMPLETED);
        assertThat(customer.amountDetails().confirmedCancellationAmount()).isEqualTo(10000L);
        assertThat(customer.amountDetails().confirmedPointRefundAmount()).isEqualTo(12L);
        assertThat(customer.amountDetails().confirmedPaymentMethodRefundAmount()).isNull();
        assertThat(customer.amountDetails().reportedAt()).isEqualTo(PAID_AT.plusSeconds(100));
        assertThat(admin.amountDetails()).isEqualTo(customer.amountDetails());
        assertThat(order.paymentResult()).isEqualTo(original);
    }

    @Test void anAbsentPointAmountRemainsUnknownWithoutHoldingBackTheCompletedRefund() {
        Order order = paid(); Refund refund = refund(order); RefundAttempt attempt = start(order, refund);
        refund.confirmPayment(order, attempt, confirmation(order, refund, attempt, null));
        RefundResponse response = customer(order, refund);
        assertThat(response.status()).isEqualTo(RefundResponse.Status.COMPLETED);
        assertThat(response.amountDetails().confirmedCancellationAmount()).isEqualTo(10000L);
        assertThat(response.amountDetails().confirmedPointRefundAmount()).isNull();
        assertThat(response.amountDetails().unconfirmedFields()).contains("confirmedPointRefundAmount");
    }

    @Test void anOlderCompletionWithoutDetailsStillCompletesAndDoesNotInventProviderMoney() {
        Order order = paid(); Refund refund = refund(order); RefundAttempt attempt = start(order, refund);
        RefundPaymentConfirmation value = confirmation(order, refund, attempt, null);
        refund.confirmPayment(order, attempt, new RefundPaymentConfirmation(value.eventId(), value.occurredAt(), value.refundId(), value.attemptId(),
                value.requestEventId(), value.orderNo(), value.paymentId(), value.buyerSubject(), value.amount(), value.currency(),
                value.source(), value.providerTransactionId(), value.cancellationIds()));
        assertThat(customer(order, refund).status()).isEqualTo(RefundResponse.Status.COMPLETED);
        assertThat(customer(order, refund).amountDetails().confirmedCancellationAmount()).isNull();
    }

    @Test void malformedAmountDetailsCannotBeIgnoredByTheExternalEventMapper() {
        Order order = paid(); Refund refund = refund(order); RefundAttempt attempt = start(order, refund);
        RefundPaymentConfirmation proof = confirmation(order, refund, attempt, 12L);
        for (RefundAmountsSnapshot invalid : List.of(
                new RefundAmountsSnapshot(9976L, 24L, List.of(new RefundCancellationAmount("cancel-1", 9999, 12L)), null),
                new RefundAmountsSnapshot(9976L, 24L, List.of(new RefundCancellationAmount("another", 10000, 12L)), null),
                new RefundAmountsSnapshot(9976L, null, List.of(new RefundCancellationAmount("cancel-1", 10000, 12L)), null),
                new RefundAmountsSnapshot(9976L, 24L, List.of(new RefundCancellationAmount("cancel-1", 10000, -1L)), null),
                new RefundAmountsSnapshot(9976L, 24L, List.of(new RefundCancellationAmount("cancel-1", 10000, 25L)), null),
                new RefundAmountsSnapshot(9976L, 24L, List.of(new RefundCancellationAmount("cancel-1", 10000, 12L)), proof.occurredAt().plusSeconds(1)),
                new RefundAmountsSnapshot(9976L, 24L, List.of(), null),
                new RefundAmountsSnapshot(9976L, 24L, List.of(new RefundCancellationAmount("cancel-1", 5000, 12L),
                        new RefundCancellationAmount("cancel-1", 5000, 12L)), null))) {
            RefundPaymentConfirmedEvent event = new RefundPaymentConfirmedEvent(proof.eventId(), proof.occurredAt(), proof.refundId(), proof.attemptId(),
                    proof.requestEventId(), proof.orderNo(), proof.paymentId(), proof.buyerSubject(), proof.amount(), proof.currency(),
                    proof.source(), proof.providerTransactionId(), proof.cancellationIds(), invalid);
            assertThatThrownBy(() -> RefundPaymentConfirmationMapper.from(envelope(event), event)).isInstanceOf(RefundCompletionRejected.class);
        }
    }

    @Test void missingAllAmountsIsDifferentFromExplicitZeroPointsAndNeverBecomesCash() {
        RefundAmountDetailsResponse unknown = RefundAmountDetailsResponse.from(RefundAmountView.unknown(10000));
        assertThat(unknown.confirmedPointRefundAmount()).isNull();
        RefundAmountDetailsResponse zero = RefundAmountDetailsResponse.from(new RefundAmountView(10000, 9976L, 24L, 10000L,
                0L, PAID_AT, "PG_RESPONSE"));
        assertThat(zero.confirmedPointRefundAmount()).isZero();
        assertThat(zero.unconfirmedFields()).doesNotContain("confirmedPointRefundAmount");
        assertThat(zero.confirmedPaymentMethodRefundAmount()).isNull();
    }

    @Test void aLateResponseCanFillUnknownPointsWithoutChangingTheFirstCompletion() {
        Order order = paid(); Refund refund = refund(order); RefundAttempt attempt = start(order, refund);
        RefundPaymentConfirmation first = confirmation(order, refund, attempt, null);
        refund.confirmPayment(order, attempt, first);
        RefundPaymentConfirmation late = response(confirmation(order, refund, attempt, 12L), PAID_AT.plusSeconds(101));
        refund.recordExecutionSuccess(order, attempt, new RefundExecutionSuccess(late, first.eventId()));
        RefundResponse response = customer(order, refund);
        assertThat(response.completedAt()).isEqualTo(first.occurredAt());
        assertThat(refund.getPaymentConfirmationSource()).isEqualTo("PG_QUERY_UNATTRIBUTED");
        assertThat(response.amountDetails().confirmedPointRefundAmount()).isEqualTo(12L);
        assertThat(response.amountDetails().reportedAt()).isEqualTo(late.occurredAt());
        assertThat(attempt.getStatus()).isEqualTo(RefundAttemptStatus.SUCCEEDED);
    }

    @Test void anEarlierQueryCannotEraseKnownPointsOrMoveTheDetailTimestampBackwards() {
        Order order = paid(); Refund refund = refund(order); RefundAttempt attempt = start(order, refund);
        RefundPaymentConfirmation first = confirmation(order, refund, attempt, null);
        RefundPaymentConfirmation late = response(confirmation(order, refund, attempt, 12L), PAID_AT.plusSeconds(101));
        refund.recordExecutionSuccess(order, attempt, new RefundExecutionSuccess(late, first.eventId()));
        refund.confirmPayment(order, attempt, first);
        RefundResponse response = customer(order, refund);
        assertThat(response.status()).isEqualTo(RefundResponse.Status.COMPLETED);
        assertThat(response.completedAt()).isEqualTo(first.occurredAt());
        assertThat(response.amountDetails().confirmedPointRefundAmount()).isEqualTo(12L);
        assertThat(response.amountDetails().reportedAt()).isEqualTo(late.occurredAt());
    }

    @Test void conflictingKnownPointsRejectTheLateSuccessBeforeMutatingTheAttempt() {
        Order order = paid(); Refund refund = refund(order); RefundAttempt attempt = start(order, refund);
        RefundPaymentConfirmation first = confirmation(order, refund, attempt, 12L); refund.confirmPayment(order, attempt, first);
        RefundPaymentConfirmation changed = response(confirmation(order, refund, attempt, 13L), PAID_AT.plusSeconds(101));
        assertThatThrownBy(() -> refund.recordExecutionSuccess(order, attempt, new RefundExecutionSuccess(changed, first.eventId())))
                .isInstanceOf(RefundCompletionRejected.class);
        assertThat(attempt.getStatus()).isEqualTo(RefundAttemptStatus.PENDING);
        assertThat(customer(order, refund).amountDetails().confirmedPointRefundAmount()).isEqualTo(12L);
    }

    @Test void invalidPointSumsAndAProviderBreakdownThatContradictsTheOrderAreRejected() {
        assertThatThrownBy(() -> new ReportedRefundAmounts(10000, new ProviderPaymentAmounts(9976, 24),
                List.of(new ReportedRefundCancellation("one", 5000, 13L), new ReportedRefundCancellation("two", 5000, 12L)), null))
                .isInstanceOf(RefundCompletionRejected.class);
        Order order = paid(); Refund refund = refund(order); RefundAttempt attempt = start(order, refund);
        RefundPaymentConfirmation first = confirmation(order, refund, attempt, 12L);
        RefundPaymentConfirmation changed = new RefundPaymentConfirmation(first.eventId(), first.occurredAt(), first.refundId(), first.attemptId(),
                first.requestEventId(), first.orderNo(), first.paymentId(), first.buyerSubject(), first.amount(), first.currency(), first.source(),
                first.providerTransactionId(), first.cancellationIds(), new ReportedRefundAmounts(10000, new ProviderPaymentAmounts(9975, 25),
                        List.of(new ReportedRefundCancellation("cancel-1", 10000, 12L)), null));
        assertThatThrownBy(() -> refund.confirmPayment(order, attempt, changed)).isInstanceOf(RefundCompletionRejected.class);
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.PENDING_PAYMENT_REFUND);
        assertThat(refund.getAmountProjection()).isNull();
    }

    @Test void partialPointInformationIsNotSummedAsIfTheOtherCancellationsReportedZero() {
        ReportedRefundAmounts value = new ReportedRefundAmounts(10000, new ProviderPaymentAmounts(9976, 24),
                List.of(new ReportedRefundCancellation("one", 5000, 12L), new ReportedRefundCancellation("two", 5000, null)), null);
        assertThat(value.confirmedPointAmount()).isNull();
        ReportedRefundAmounts reordered = new ReportedRefundAmounts(10000, value.paymentAmounts(),
                List.of(value.cancellations().getLast(), value.cancellations().getFirst()), null);
        assertThat(reordered.fingerprint()).isEqualTo(value.fingerprint());
    }

    private RefundPaymentConfirmation response(RefundPaymentConfirmation input, Instant at) {
        return new RefundPaymentConfirmation(input.eventId(), at, input.refundId(), input.attemptId(), input.requestEventId(), input.orderNo(),
                input.paymentId(), input.buyerSubject(), input.amount(), input.currency(), "PG_RESPONSE", input.providerTransactionId(),
                input.cancellationIds(), input.amountDetails());
    }

    private RefundResponse customer(Order order, Refund refund) {
        when(orders.findByOrderNoAndBuyerSubject(order.getOrderNo(), order.getBuyerSubject())).thenReturn(Optional.of(order));
        when(refunds.findByOrderNo(order.getOrderNo())).thenReturn(Optional.of(refund));
        return RefundResponse.from(new ReadRefund(orders, refunds, rounds, Clock.systemUTC(), mock(RefundAttemptRepository.class), Duration.ofMinutes(10))
                .execute(order.getOrderNo(), order.getBuyerSubject()));
    }

    private Order paid() {
        Order order = order();
        order.apply(new PaymentResult(id(), PAID_AT, order.getOrderNo(), order.getRequestEventId(), id(), order.getBuyerSubject(),
                10000, "KRW", OrderStatus.PAID, null, null, new ProviderPaymentAmounts(9976, 24)));
        return order;
    }

    private Refund refund(Order order) { return Refund.request(order, PAID_AT.plusSeconds(60)); }

    private RefundAttempt start(Order order, Refund refund) {
        LicenseGrant grant = new LicenseGrant(id(), PAID_AT, id(), order.getOrderNo(), order.getBuyerSubject(),
                order.getProductId(), order.getProductCode(), order.getPaymentId());
        refund.confirmLicenseGrant(grant);
        LicenseSuspensionConfirmation suspension = new LicenseSuspensionConfirmation(id(), PAID_AT.plusSeconds(61),
                refund.getSuspensionRequestEventId(), refund.getRefundId(), grant.licenseId(), order.getOrderNo(), order.getBuyerSubject(),
                order.getProductId(), order.getProductCode(), order.getPaymentId());
        RefundAttempt attempt = RefundAttempt.afterSuspension(refund, suspension, PAID_AT.plusSeconds(62));
        refund.recordExecution(attempt);
        return attempt;
    }

    private RefundPaymentConfirmation confirmation(Order order, Refund refund, RefundAttempt attempt, Long points) {
        return new RefundPaymentConfirmation(id(), PAID_AT.plusSeconds(100), refund.getRefundId(), attempt.getAttemptId(),
                attempt.getExecutionRequestEventId(), order.getOrderNo(), order.getPaymentId(), order.getBuyerSubject(), 10000, "KRW",
                "PG_QUERY_UNATTRIBUTED", "pg-original", List.of("cancel-1"), new ReportedRefundAmounts(10000,
                        new ProviderPaymentAmounts(9976, 24), List.of(new ReportedRefundCancellation("cancel-1", 10000, points)), PAID_AT.plusSeconds(90)));
    }
}
