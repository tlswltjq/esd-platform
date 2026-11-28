package com.stove.payment.api.listener;

import static com.stove.payment.PaymentFixtures.id;
import static com.stove.payment.PaymentFixtures.request;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stove.common.contract.EventMessage;
import com.stove.common.contract.payload.OrderExpiredEvent;
import com.stove.common.web.error.BusinessException;
import com.stove.payment.core.domain.PaymentOrderRequest;
import org.junit.jupiter.api.Test;

class OrderExpiryMapperTest {
    private final PaymentOrderRequest source=request();
    private final OrderExpiredEvent event=new OrderExpiredEvent(id(),source.occurredAt().plusSeconds(1800),source.orderNo(),source.eventId(),source.buyerSubject(),source.amount(),source.currency(),source.occurredAt().plusSeconds(1800));
    private EventMessage message() { return new EventMessage(event.eventId(),event.eventType(),event.orderNo(),""); }
    @Test void validatesMessageAndConvertsToInternalInput() {
        assertThat(OrderExpiryMapper.from(message(),event).requestEventId()).isEqualTo(source.eventId());
    }
    @Test void aWrongKeyOrEventIdentityCannotReachTheBusinessService() {
        assertThatThrownBy(()->OrderExpiryMapper.from(new EventMessage(id(),event.eventType(),event.orderNo(),""),event)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->OrderExpiryMapper.from(new EventMessage(event.eventId(),event.eventType(),id(),""),event)).isInstanceOf(BusinessException.class);
    }
    @Test void anEarlyExpiryAndMalformedPurchaseAreRejectedAtTheBoundary() {
        OrderExpiredEvent early=new OrderExpiredEvent(event.eventId(),event.expiresAt().minusSeconds(1),event.orderNo(),event.requestEventId(),event.buyerSubject(),event.amount(),event.currency(),event.expiresAt());
        assertThatThrownBy(()->OrderExpiryMapper.from(message(),early)).isInstanceOf(BusinessException.class);
        OrderExpiredEvent bad=new OrderExpiredEvent(event.eventId(),event.occurredAt(),"invalid",event.requestEventId(),event.buyerSubject(),event.amount(),event.currency(),event.expiresAt());
        assertThatThrownBy(()->OrderExpiryMapper.from(new EventMessage(event.eventId(),event.eventType(),"invalid",""),bad)).isInstanceOf(BusinessException.class);
    }
}
