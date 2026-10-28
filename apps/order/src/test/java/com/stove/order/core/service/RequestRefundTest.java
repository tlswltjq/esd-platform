package com.stove.order.core.service;

import static com.stove.order.OrderFixtures.id;
import static com.stove.order.OrderFixtures.order;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.stove.common.kafka.contract.payload.LicenseSuspensionRequestedEvent;
import com.stove.common.messaging.outbox.OutboxRecorder;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.order.core.domain.Order;
import com.stove.order.core.domain.OrderRepository;
import com.stove.order.core.domain.LicenseGrant;
import com.stove.order.core.domain.OrderLicenseGrant;
import com.stove.order.core.domain.OrderLicenseGrantRepository;
import com.stove.order.core.domain.OrderStatus;
import com.stove.order.core.domain.PaymentResult;
import com.stove.order.core.domain.Refund;
import com.stove.order.core.domain.RefundRepository;
import com.stove.order.core.domain.RefundStatus;
import com.stove.order.core.domain.RefundReviewRoundRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class RequestRefundTest {
    private static final Instant PAYMENT_CONFIRMED_AT = Instant.parse("2026-10-25T10:00:00.123456Z");
    private static final Instant RECEIVED_AT = Instant.parse("2026-10-25T13:00:00.123456Z");

    @Mock private OrderRepository orders;
    @Mock private RefundRepository refunds;
    @Mock private OrderLicenseGrantRepository grants;
    @Mock private OutboxRecorder outbox;
    @Mock private RefundReviewRoundRepository rounds;

    @Test
    void anEligibleOwnersRequestSavesTheRefundAndRequestsOnlyLicenseSuspension() {
        Order paid = paidOrder();
        PaymentResult originalPayment = paid.paymentResult();
        Clock clock = Clock.fixed(RECEIVED_AT, ZoneOffset.UTC);
        RequestRefund request = new RequestRefund(orders, refunds, grants, outbox, clock, rounds);
        when(orders.lockByOrderNo(paid.getOrderNo())).thenReturn(Optional.of(paid));
        when(refunds.findByOrderNo(paid.getOrderNo())).thenReturn(Optional.empty());
        when(refunds.save(any(Refund.class))).thenAnswer(call -> call.getArgument(0, Refund.class));
        when(grants.findByOrderNo(paid.getOrderNo())).thenReturn(Optional.of(OrderLicenseGrant.record(
                new LicenseGrant(id(), PAYMENT_CONFIRMED_AT.plusSeconds(60), id(), paid.getOrderNo(),
                        paid.getBuyerSubject(), paid.getProductId(), paid.getProductCode(), paid.getPaymentId()))));

        Refund received = request.execute(paid.getBuyerSubject(), paid.getOrderNo());

        ArgumentCaptor<Refund> savedRefund = ArgumentCaptor.forClass(Refund.class);
        InOrder lockedLookup = inOrder(orders, refunds);
        lockedLookup.verify(orders).lockByOrderNo(paid.getOrderNo());
        lockedLookup.verify(refunds).findByOrderNo(paid.getOrderNo());
        lockedLookup.verify(refunds).save(savedRefund.capture());
        Refund saved = savedRefund.getValue();
        assertThat(saved.getRefundId()).isNotBlank();
        assertThat(saved.getOrderNo()).isEqualTo(paid.getOrderNo());
        assertThat(saved.getReceivedAt()).isEqualTo(RECEIVED_AT);
        assertThat(saved.isAutomaticRefundEligible()).isTrue();
        assertThat(saved.getStatus()).isEqualTo(RefundStatus.PENDING_LICENSE_SUSPENSION);
        assertThat(received).usingRecursiveComparison().isEqualTo(saved);
        assertThat(paid.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(paid.paymentResult()).isEqualTo(originalPayment);

        ArgumentCaptor<LicenseSuspensionRequestedEvent> event =
                ArgumentCaptor.forClass(LicenseSuspensionRequestedEvent.class);
        verify(outbox).record(eq("Order"), eq(paid.getOrderNo()), event.capture());
        LicenseSuspensionRequestedEvent requested = event.getValue();
        assertThat(requested.eventId()).isNotBlank();
        assertThat(requested.eventId()).isNotEqualTo(originalPayment.eventId());
        assertThat(requested.occurredAt()).isEqualTo(RECEIVED_AT);
        assertThat(requested.eventType()).isEqualTo("LicenseSuspensionRequested");
        assertThat(requested.topic()).isEqualTo("stove.order.v1");
        assertThat(requested.partitionKey()).isEqualTo(paid.getOrderNo());
        assertThat(requested.refundId()).isEqualTo(saved.getRefundId());
        assertThat(requested.orderNo()).isEqualTo(paid.getOrderNo());
        assertThat(requested.buyerSubject()).isEqualTo(paid.getBuyerSubject());
        assertThat(requested.productId()).isEqualTo(paid.getProductId());
        assertThat(requested.productCode()).isEqualTo(paid.getProductCode());
        assertThat(requested.paymentId()).isEqualTo(originalPayment.paymentId());
        verifyNoMoreInteractions(outbox);
    }

    @Test
    void aLateOwnersRequestIsSavedForReviewWithoutRequestingSuspension() {
        Order paid = paidOrder();
        PaymentResult originalPayment = paid.paymentResult();
        Instant lateReceipt = PAYMENT_CONFIRMED_AT.plus(Duration.ofHours(4)).plusNanos(1_000);
        RequestRefund request = new RequestRefund(orders, refunds, grants, outbox,
                Clock.fixed(lateReceipt, ZoneOffset.UTC), rounds);
        when(orders.lockByOrderNo(paid.getOrderNo())).thenReturn(Optional.of(paid));
        when(refunds.findByOrderNo(paid.getOrderNo())).thenReturn(Optional.empty());
        when(refunds.save(any(Refund.class))).thenAnswer(call -> call.getArgument(0, Refund.class));

        Refund received = request.execute(paid.getBuyerSubject(), paid.getOrderNo());

        ArgumentCaptor<Refund> saved = ArgumentCaptor.forClass(Refund.class);
        verify(refunds).save(saved.capture());
        assertThat(received).usingRecursiveComparison().isEqualTo(saved.getValue());
        assertThat(received.getRefundId()).isNotBlank();
        assertThat(received.getOrderNo()).isEqualTo(paid.getOrderNo());
        assertThat(received.getReceivedAt()).isEqualTo(lateReceipt);
        assertThat(received.isAutomaticRefundEligible()).isFalse();
        assertThat(received.getStatus()).isEqualTo(RefundStatus.PENDING_REVIEW);
        assertThat(paid.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(paid.paymentResult()).isEqualTo(originalPayment);
        verifyNoInteractions(outbox);
    }

    @Test
    void aDuplicateAfterTheDeadlineKeepsTheOriginalRefundWithoutAnotherSuspensionRequest() {
        Order paid = paidOrder();
        PaymentResult originalPayment = paid.paymentResult();
        Refund previous = Refund.request(paid, RECEIVED_AT);
        RequestRefund request = new RequestRefund(orders, refunds, grants, outbox,
                Clock.fixed(PAYMENT_CONFIRMED_AT.plus(Duration.ofHours(6)), ZoneOffset.UTC), rounds);
        when(orders.lockByOrderNo(paid.getOrderNo())).thenReturn(Optional.of(paid));
        when(refunds.findByOrderNo(paid.getOrderNo())).thenReturn(Optional.of(previous));

        Refund received = request.execute(paid.getBuyerSubject(), paid.getOrderNo());

        assertThat(received.getRefundId()).isEqualTo(previous.getRefundId());
        assertThat(received.getReceivedAt()).isEqualTo(RECEIVED_AT);
        assertThat(received.isAutomaticRefundEligible()).isTrue();
        assertThat(received.getStatus()).isEqualTo(RefundStatus.PENDING_LICENSE_GRANT);
        assertThat(paid.paymentResult()).isEqualTo(originalPayment);
        verify(refunds, never()).save(any(Refund.class));
        verifyNoInteractions(outbox);
    }

    @Test
    void aMissingOrderDoesNotCreateARefundOrRequestSuspension() {
        RequestRefund request = requestAt(RECEIVED_AT);
        String orderNo = id();
        when(orders.lockByOrderNo(orderNo)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> request.execute("usr_buyer", orderNo))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));

        verifyNoInteractions(refunds, outbox);
    }

    @Test
    void anotherBuyersOrderIsRejectedBeforeLookingUpARefund() {
        Order paid = paidOrder();
        RequestRefund request = requestAt(RECEIVED_AT);
        when(orders.lockByOrderNo(paid.getOrderNo())).thenReturn(Optional.of(paid));

        assertThatThrownBy(() -> request.execute("usr_other", paid.getOrderNo()))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));

        verifyNoInteractions(refunds, outbox);
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"PENDING", "FAILED"})
    void anUnpaidOrderCannotBeSavedAsARefund(OrderStatus status) {
        Order unpaid = order();
        if (status == OrderStatus.FAILED) {
            unpaid.apply(new PaymentResult(id(), PAYMENT_CONFIRMED_AT, unpaid.getOrderNo(),
                    unpaid.getRequestEventId(), id(), unpaid.getBuyerSubject(), unpaid.getAmount(),
                    unpaid.getCurrency(), OrderStatus.FAILED, "DECLINED", "승인이 거절되었습니다."));
        }
        RequestRefund request = requestAt(RECEIVED_AT);
        when(orders.lockByOrderNo(unpaid.getOrderNo())).thenReturn(Optional.of(unpaid));

        assertThatThrownBy(() -> request.execute(unpaid.getBuyerSubject(), unpaid.getOrderNo()))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.CONFLICT));

        assertThat(unpaid.getStatus()).isEqualTo(status);
        verify(refunds, never()).save(any(Refund.class));
        verifyNoInteractions(outbox);
    }

    @ParameterizedTest
    @MethodSource("invalidBuyers")
    void rejectsAnInvalidBuyerBeforeReadingOrWriting(String buyer) {
        RequestRefund request = requestAt(RECEIVED_AT);

        assertThatThrownBy(() -> request.execute(buyer, id()))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));

        verifyNoInteractions(orders, refunds, outbox);
    }

    @ParameterizedTest
    @MethodSource("invalidOrderNumbers")
    void rejectsAnInvalidOrderNumberBeforeReadingOrWriting(String orderNo) {
        RequestRefund request = requestAt(RECEIVED_AT);

        assertThatThrownBy(() -> request.execute("usr_buyer", orderNo))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));

        verifyNoInteractions(orders, refunds, outbox);
    }

    @Test
    void lockWaitDoesNotReplaceTheOriginalReceiptTimeWithTheProcessingTime() {
        Order paid = paidOrder();
        Instant receipt = PAYMENT_CONFIRMED_AT.plus(Duration.ofHours(4));
        AtomicReference<Instant> currentTime = new AtomicReference<>(receipt);
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenAnswer(call -> currentTime.get());
        RequestRefund request = new RequestRefund(orders, refunds, grants, outbox, clock, rounds);
        when(orders.lockByOrderNo(paid.getOrderNo())).thenAnswer(call -> {
            currentTime.set(receipt.plusSeconds(30));
            return Optional.of(paid);
        });
        when(refunds.save(any(Refund.class))).thenAnswer(call -> call.getArgument(0, Refund.class));

        Refund received = request.execute(paid.getBuyerSubject(), paid.getOrderNo());

        assertThat(currentTime.get()).isAfter(receipt);
        assertThat(received.getReceivedAt()).isEqualTo(receipt);
        assertThat(received.isAutomaticRefundEligible()).isTrue();
        assertThat(received.getStatus()).isEqualTo(RefundStatus.PENDING_LICENSE_GRANT);
    }

    @Test
    void aDuplicateReviewRequestKeepsItsOriginalReceiptAndReviewStatus() {
        Order paid = paidOrder();
        Instant originalReceipt = PAYMENT_CONFIRMED_AT.plus(Duration.ofHours(5));
        Refund previous = Refund.request(paid, originalReceipt);
        RequestRefund request = requestAt(PAYMENT_CONFIRMED_AT.plus(Duration.ofHours(6)));
        when(orders.lockByOrderNo(paid.getOrderNo())).thenReturn(Optional.of(paid));
        when(refunds.findByOrderNo(paid.getOrderNo())).thenReturn(Optional.of(previous));

        Refund received = request.execute(paid.getBuyerSubject(), paid.getOrderNo());

        assertThat(received.getRefundId()).isEqualTo(previous.getRefundId());
        assertThat(received.getReceivedAt()).isEqualTo(originalReceipt);
        assertThat(received.isAutomaticRefundEligible()).isFalse();
        assertThat(received.getStatus()).isEqualTo(RefundStatus.PENDING_REVIEW);
        verify(refunds, never()).save(any(Refund.class));
        verifyNoInteractions(outbox);
    }

    private RequestRefund requestAt(Instant receivedAt) {
        return new RequestRefund(orders, refunds, grants, outbox, Clock.fixed(receivedAt, ZoneOffset.UTC), rounds);
    }

    @Test
    void aRefundWithoutGrantEvidenceWaitsWithoutIssuingAnyWorkToOtherServices() {
        Order paid = paidOrder();
        when(orders.lockByOrderNo(paid.getOrderNo())).thenReturn(Optional.of(paid));

        Refund received = requestAt(RECEIVED_AT).execute(paid.getBuyerSubject(), paid.getOrderNo());

        assertThat(received.getStatus()).isEqualTo(RefundStatus.PENDING_LICENSE_GRANT);
        assertThat(received.getReceivedAt()).isEqualTo(RECEIVED_AT);
        assertThat(received.isAutomaticRefundEligible()).isTrue();
        verify(refunds).save(received);
        verifyNoInteractions(outbox);
    }

    private static Stream<String> invalidBuyers() {
        return Stream.of(null, "", " ", "x".repeat(101));
    }

    private static Stream<String> invalidOrderNumbers() {
        return Stream.of(null, "", " ", "x".repeat(37));
    }

    private static Order paidOrder() {
        Order paid = order();
        // 저장된 과거 주문을 재현한다. 주문 생성 시각을 기한의 시작으로 쓰면 이 요청은 잘못 거절된다.
        ReflectionTestUtils.setField(paid, "createdAt", PAYMENT_CONFIRMED_AT.minus(Duration.ofHours(2)));
        paid.apply(new PaymentResult(id(), PAYMENT_CONFIRMED_AT, paid.getOrderNo(),
                paid.getRequestEventId(), id(), paid.getBuyerSubject(), paid.getAmount(),
                paid.getCurrency(), OrderStatus.PAID, null, null));
        ReflectionTestUtils.setField(paid, "updatedAt", PAYMENT_CONFIRMED_AT.plus(Duration.ofMinutes(5)));
        return paid;
    }
}
