package com.stove.payment.core.domain;

import static com.stove.payment.PaymentFixtures.id;
import static com.stove.payment.PaymentFixtures.request;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stove.common.web.error.BusinessException;
import org.junit.jupiter.api.Test;

class PaymentOrderLifecycleTest {
    private final PaymentOrderRequest request=request();
    private final PaymentOrderLifecycle lifecycle=PaymentOrderLifecycle.from(request);
    private OrderExpiryInput expiry() {
        return new OrderExpiryInput(id(),request.occurredAt().plusSeconds(1801),request.orderNo(),request.eventId(),request.buyerSubject(),request.amount(),request.currency(),request.occurredAt().plusSeconds(1800));
    }
    @Test void confirmationIsImmutableAndDuplicateDeliveryKeepsTheOriginalFact() {
        OrderExpiryInput expiry=expiry(); assertThat(lifecycle.recordExpiry(expiry)).isTrue();
        assertThat(lifecycle.recordExpiry(expiry)).isFalse(); assertThat(lifecycle.expired()).isTrue();
        assertThat(lifecycle.getExpiredAt()).isEqualTo(expiry.occurredAt()); assertThat(lifecycle.getExpiryEventId()).isEqualTo(expiry.eventId());
    }
    @Test void anotherBuyerCannotExpireThisOrder() {
        OrderExpiryInput e=expiry(); assertThatThrownBy(()->lifecycle.recordExpiry(new OrderExpiryInput(e.eventId(),e.occurredAt(),e.orderNo(),e.requestEventId(),"other",e.amount(),e.currency(),e.expiresAt()))).isInstanceOf(BusinessException.class);
    }
    @Test void changedPriceOrSourceCannotExpireThisOrder() {
        OrderExpiryInput e=expiry(); assertThatThrownBy(()->lifecycle.recordExpiry(new OrderExpiryInput(e.eventId(),e.occurredAt(),e.orderNo(),e.requestEventId(),e.buyerSubject(),e.amount()+1,e.currency(),e.expiresAt()))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->lifecycle.recordExpiry(new OrderExpiryInput(e.eventId(),e.occurredAt(),e.orderNo(),id(),e.buyerSubject(),e.amount(),e.currency(),e.expiresAt()))).isInstanceOf(BusinessException.class);
    }
    @Test void replayCannotReplaceTheOriginalExpiryTimeOrEventIdentity() {
        OrderExpiryInput e=expiry(); lifecycle.recordExpiry(e);
        assertThatThrownBy(()->lifecycle.recordExpiry(new OrderExpiryInput(e.eventId(),e.occurredAt().plusSeconds(1),e.orderNo(),e.requestEventId(),e.buyerSubject(),e.amount(),e.currency(),e.expiresAt()))).isInstanceOf(BusinessException.class);
    }
    @Test void aLateOrderPlacedCannotReopenOrChangeTheOrderExpiry() {
        lifecycle.recordExpiry(expiry()); lifecycle.requireOriginal(request);
        PaymentOrderRequest changed=new PaymentOrderRequest(request.eventId(),request.occurredAt().plusSeconds(1),request.orderNo(),request.buyerSubject(),request.productId(),request.productCode(),request.title(),request.contentVersion(),request.listAmount(),request.discountAmount(),request.amount(),request.currency());
        assertThatThrownBy(()->lifecycle.requireOriginal(changed)).isInstanceOf(BusinessException.class);
    }
    @Test void anUnconfirmedClockDeadlineIsNotAnExpiryReceipt() {
        assertThat(lifecycle.expired()).isFalse(); assertThat(lifecycle.getExpiredAt()).isNull();
    }
    @Test void anExpiryBeforeTheDeadlineIsInvalidInput() {
        OrderExpiryInput e=expiry(); assertThatThrownBy(()->new OrderExpiryInput(e.eventId(),e.expiresAt().minusNanos(1000),e.orderNo(),e.requestEventId(),e.buyerSubject(),e.amount(),e.currency(),e.expiresAt())).isInstanceOf(BusinessException.class);
    }
}
