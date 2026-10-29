package com.stove.order.core.domain;

import static com.stove.order.OrderFixtures.id;
import static com.stove.order.OrderFixtures.order;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stove.common.web.error.BusinessException;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class OrderPaymentLifecycleTest {
    @Test void deadlineIsThirtyMinutesFromOriginalCreation() {
        Order order = order();
        Instant deadline = order.getCreatedAt().plus(Duration.ofMinutes(30));
        assertThat(order.getExpiresAt()).isEqualTo(deadline);
    }

    @Test void admissionBeforeDeadlineKeepsTermsAndFixesThePaymentConnection() {
        Order order = order();
        PaymentAdmissionInput input = input(order);
        Instant at = deadline(order).minusNanos(1000);
        assertThat(order.beginPaymentProcessing(input, at)).isTrue();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_PROCESSING);
        assertThat(order.getPaymentId()).isEqualTo(input.paymentId());
        assertThat(order.getUpdatedAt()).isEqualTo(at);
        assertThat(order.getAmount()).isEqualTo(10000);
        assertThat(order.getExpiresAt()).isEqualTo(deadline(order));
        assertThat(order.paymentResult()).isNull();
    }

    @Test void theDeadlineItselfRejectsAdmissionAndExpiresTheOrder() {
        Order order = order();
        assertThat(order.beginPaymentProcessing(input(order), deadline(order))).isFalse();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.EXPIRED);
        assertThat(order.getPaymentId()).isNull();
        assertThat(order.getUpdatedAt()).isEqualTo(deadline(order));
    }

    @Test void anAdmittedPaymentCanCompleteAfterTheDeadlineWithoutBeingExpired() {
        Order order = order();
        PaymentAdmissionInput input = input(order);
        assertThat(order.beginPaymentProcessing(input, deadline(order).minusSeconds(1))).isTrue();
        Instant late = deadline(order).plusSeconds(60);
        assertThat(order.expire(late)).isFalse();
        order.apply(paid(order, input.paymentId(), late));
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(order.getResultOccurredAt()).isEqualTo(late);
    }

    @Test void aPendingOrderExpiresOnlyAtItsDeadlineAndKeepsItsOriginalTerms() {
        Order order = order();
        assertThat(order.expire(deadline(order).minusNanos(1000))).isFalse();
        assertThat(order.expire(deadline(order))).isTrue();
        Instant updated = order.getUpdatedAt();
        assertThat(order.expire(deadline(order).plusSeconds(20))).isFalse();
        assertThat(order.getUpdatedAt()).isEqualTo(updated);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.EXPIRED);
        assertThat(order.getAmount()).isEqualTo(10000);
    }

    @Test void anExpiredOrderDoesNotSilentlyAcceptAResultWithoutAdmission() {
        Order order = order();
        order.expire(deadline(order));
        assertThatThrownBy(() -> order.apply(paid(order, id(), deadline(order).plusSeconds(1))))
                .isInstanceOf(BusinessException.class);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.EXPIRED);
        assertThat(order.paymentResult()).isNull();
    }

    @Test void processingRejectsAnotherPaymentAndAnotherAdmission() {
        Order order = order();
        PaymentAdmissionInput input = input(order);
        order.beginPaymentProcessing(input, order.getCreatedAt());
        assertThatThrownBy(() -> order.beginPaymentProcessing(input(order), order.getCreatedAt()))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> order.apply(paid(order, id(), deadline(order).plusSeconds(1))))
                .isInstanceOf(BusinessException.class);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_PROCESSING);
        assertThat(order.getPaymentId()).isEqualTo(input.paymentId());
    }

    @Test void mismatchedPurchaseCannotAdmitOrExpireAnOrder() {
        Order order = order();
        PaymentAdmissionInput wrong = new PaymentAdmissionInput(id(), order.getOrderNo(), id(), id(),
                order.getBuyerSubject(), order.getAmount(), order.getCurrency());
        assertThatThrownBy(() -> order.beginPaymentProcessing(wrong, deadline(order)))
                .isInstanceOf(BusinessException.class);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
    }

    @Test void invalidObservationTimesCannotMutateTheOrder() {
        Order order = order();
        assertThatThrownBy(() -> order.expire(order.getCreatedAt().minusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> order.beginPaymentProcessing(input(order), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private Instant deadline(Order order) { return order.getCreatedAt().plus(Duration.ofMinutes(30)); }
    private PaymentAdmissionInput input(Order order) {
        return new PaymentAdmissionInput(id(), order.getOrderNo(), order.getRequestEventId(), id(),
                order.getBuyerSubject(), order.getAmount(), order.getCurrency());
    }
    private PaymentResult paid(Order order, String paymentId, Instant at) {
        return new PaymentResult(id(), at, order.getOrderNo(), order.getRequestEventId(), paymentId,
                order.getBuyerSubject(), order.getAmount(), order.getCurrency(), OrderStatus.PAID, null, null);
    }
}
