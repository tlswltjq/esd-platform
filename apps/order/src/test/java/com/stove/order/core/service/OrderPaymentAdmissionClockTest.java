package com.stove.order.core.service;

import static com.stove.order.OrderFixtures.id;
import static com.stove.order.OrderFixtures.order;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import com.stove.common.web.error.BusinessException;
import com.stove.order.core.domain.Order;
import com.stove.order.core.domain.OrderPaymentAdmissionRepository;
import com.stove.order.core.domain.OrderPaymentAdmission;
import com.stove.order.core.domain.PaymentAdmissionFailureInput;
import com.stove.order.core.domain.OrderRepository;
import com.stove.order.core.domain.OrderStatus;
import com.stove.order.core.domain.PaymentAdmissionInput;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class OrderPaymentAdmissionClockTest {
    @Test void aLockWaitThatCrossesTheDeadlineRejectsAdmissionUsingTheTimeAfterTheLock() {
        Order order = order();
        OrderRepository orders = mock(OrderRepository.class);
        OrderPaymentAdmissionRepository admissions = mock(OrderPaymentAdmissionRepository.class);
        Clock clock = mock(Clock.class);
        AtomicReference<Instant> time = new AtomicReference<>(order.getExpiresAt().minusSeconds(1));
        when(orders.lockByOrderNo(order.getOrderNo())).thenAnswer(call -> {
            time.set(order.getExpiresAt());
            return Optional.of(order);
        });
        when(clock.instant()).thenAnswer(call -> time.get());
        PaymentAdmissionInput input = new PaymentAdmissionInput(id(), order.getOrderNo(), order.getRequestEventId(),
                id(), order.getBuyerSubject(), order.getAmount(), order.getCurrency());
        RequestOrderPaymentProcessing service = new RequestOrderPaymentProcessing(orders, admissions, clock,mock(RecordOrderExpiry.class));
        assertThat(service.execute(input)).isEmpty();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.EXPIRED);
        assertThat(order.getPaymentId()).isNull();
    }

    @Test void observingAnExpiredProcessingOrderDoesNotEraseItsAdmission() {
        Order order = order();
        PaymentAdmissionInput input = new PaymentAdmissionInput(id(), order.getOrderNo(), order.getRequestEventId(),
                id(), order.getBuyerSubject(), order.getAmount(), order.getCurrency());
        order.beginPaymentProcessing(input, order.getCreatedAt());
        OrderRepository orders = mock(OrderRepository.class);
        when(orders.lockByOrderNo(order.getOrderNo())).thenReturn(Optional.of(order));
        CloseExpiredOrder close = new CloseExpiredOrder(orders, Clock.fixed(order.getExpiresAt().plusSeconds(60), ZoneOffset.UTC),mock(RecordOrderExpiry.class));
        assertThat(close.execute(order.getOrderNo())).isFalse();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_PROCESSING);
        assertThat(order.getPaymentId()).isEqualTo(input.paymentId());
    }

    @Test void aVerifiedFailureAtTheDeadlinePublishesOneExpiryAndAnOldClosureReplayDoesNotPublishAgain() {
        Order order=order();
        PaymentAdmissionInput admission=new PaymentAdmissionInput(id(),order.getOrderNo(),order.getRequestEventId(),id(),order.getBuyerSubject(),order.getAmount(),order.getCurrency());
        order.beginPaymentProcessing(admission,order.getCreatedAt());
        OrderPaymentAdmission receipt=OrderPaymentAdmission.record(order,admission);
        OrderRepository orders=mock(OrderRepository.class);
        OrderPaymentAdmissionRepository admissions=mock(OrderPaymentAdmissionRepository.class);
        RecordOrderExpiry expiry=mock(RecordOrderExpiry.class);
        when(orders.lockByOrderNo(order.getOrderNo())).thenReturn(Optional.of(order));
        when(admissions.findByAdmissionId(admission.admissionId())).thenReturn(Optional.of(receipt));
        when(admissions.findFirstByOrderNoOrderByIdDesc(order.getOrderNo())).thenReturn(Optional.of(receipt));
        RecordOrderPaymentAttemptFailure service=new RecordOrderPaymentAttemptFailure(orders,admissions,Clock.fixed(order.getExpiresAt(),ZoneOffset.UTC),expiry);
        PaymentAdmissionFailureInput failure=new PaymentAdmissionFailureInput(admission.admissionId(),order.getOrderNo(),admission.paymentId(),"tx-failed",order.getExpiresAt());
        assertThat(service.execute(failure).getClosedOrderStatus()).isEqualTo("EXPIRED");
        assertThat(service.execute(failure).getClosedOrderStatus()).isEqualTo("EXPIRED");
        verify(expiry,times(1)).execute(order);
    }

    @Test void malformedExpiryRequestsDoNotTouchPersistence() {
        OrderRepository orders = mock(OrderRepository.class);
        CloseExpiredOrder close = new CloseExpiredOrder(orders, Clock.systemUTC(),mock(RecordOrderExpiry.class));
        assertThatThrownBy(() -> close.execute("not-an-order"))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(orders);
    }
}
