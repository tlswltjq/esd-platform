package com.stove.order.core.service;

import static com.stove.order.OrderFixtures.completed;
import static com.stove.order.OrderFixtures.failed;
import static com.stove.order.OrderFixtures.order;
import static com.stove.order.OrderFixtures.result;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.stove.common.kafka.contract.EventType;
import com.stove.common.kafka.contract.payload.OrderPaidEvent;
import com.stove.common.kafka.contract.payload.OrderPaymentFailedEvent;
import com.stove.common.messaging.inbox.ProcessedEventGuard;
import com.stove.common.messaging.outbox.OutboxRecorder;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.order.core.domain.Order;
import com.stove.order.core.domain.OrderRepository;
import com.stove.order.core.domain.OrderStatus;
import com.stove.order.core.domain.PaymentResult;
import jakarta.persistence.EntityManager;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ApplyPaymentResultTest {
    @Mock private OrderRepository orders;
    @Mock private ProcessedEventGuard inbox;
    @Mock private EntityManager entityManager;
    @Mock private OutboxRecorder outbox;
    @InjectMocks private ApplyPaymentResult apply;

    @Test
    void successfulPaymentPublishesTheStoredOrderFacts() {
        Order stored = order();
        PaymentResult input = result(completed(stored));
        when(orders.lockByOrderNo(stored.getOrderNo())).thenReturn(Optional.of(stored));
        when(inbox.firstDelivery(input.eventId(), ApplyPaymentResult.CONSUMER_GROUP,
                EventType.ORDER_PAYMENT_COMPLETED)).thenReturn(true);

        apply.execute(EventType.ORDER_PAYMENT_COMPLETED, input);

        assertThat(stored.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(stored.paymentResult()).isEqualTo(input);
        ArgumentCaptor<OrderPaidEvent> event = ArgumentCaptor.forClass(OrderPaidEvent.class);
        verify(outbox).record(eq("Order"), eq(stored.getOrderNo()), event.capture());
        OrderPaidEvent published = event.getValue();
        assertThat(published.eventId()).isNotBlank();
        assertThat(published.occurredAt()).isEqualTo(stored.getUpdatedAt());
        assertThat(published.orderNo()).isEqualTo(stored.getOrderNo());
        assertThat(published.requestEventId()).isEqualTo(stored.getRequestEventId());
        assertThat(published.paymentResultEventId()).isEqualTo(input.eventId());
        assertThat(published.paymentId()).isEqualTo(input.paymentId());
        assertThat(published.buyerSubject()).isEqualTo(stored.getBuyerSubject());
        assertThat(published.productId()).isEqualTo(stored.getProductId());
        assertThat(published.productCode()).isEqualTo(stored.getProductCode());
        assertThat(published.title()).isEqualTo(stored.getTitle());
        assertThat(published.contentVersion()).isEqualTo(stored.getContentVersion());
        assertThat(published.amount()).isEqualTo(stored.getAmount());
        assertThat(published.currency()).isEqualTo(stored.getCurrency());
        verify(entityManager).flush();
    }

    @Test
    void definitiveFailureDoesNotPublishAPaidOrder() {
        Order stored = order();
        OrderPaymentFailedEvent event = failed(stored);
        PaymentResult input = new PaymentResult(event.eventId(), event.occurredAt(), event.orderNo(),
                event.requestEventId(), event.paymentId(), event.buyerSubject(), event.amount(),
                event.currency(), OrderStatus.FAILED, event.reasonCode(), event.reason());
        when(orders.lockByOrderNo(stored.getOrderNo())).thenReturn(Optional.of(stored));
        when(inbox.firstDelivery(input.eventId(), ApplyPaymentResult.CONSUMER_GROUP,
                EventType.ORDER_PAYMENT_FAILED)).thenReturn(true);

        apply.execute(EventType.ORDER_PAYMENT_FAILED, input);

        assertThat(stored.getStatus()).isEqualTo(OrderStatus.FAILED);
        assertThat(stored.paymentResult()).isEqualTo(input);
        verifyNoInteractions(outbox);
    }

    @Test
    void identicalFinalResultDoesNotCreateAnotherPaidEvent() {
        Order stored = order();
        PaymentResult input = result(completed(stored));
        stored.apply(input);
        when(orders.lockByOrderNo(stored.getOrderNo())).thenReturn(Optional.of(stored));

        apply.execute(EventType.ORDER_PAYMENT_COMPLETED, input);

        assertThat(stored.paymentResult()).isEqualTo(input);
        verifyNoInteractions(inbox, outbox, entityManager);
    }

    @Test
    void missingOrderIsRejectedBeforeRecordingTheInbox() {
        PaymentResult input = result(completed(order()));
        when(orders.lockByOrderNo(input.orderNo())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> apply.execute(EventType.ORDER_PAYMENT_COMPLETED, input))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));

        verifyNoInteractions(inbox, outbox, entityManager);
    }

    @Test
    void aResultEventAlreadyUsedByAnotherOrderCannotFinalizeThisOrder() {
        Order stored = order();
        PaymentResult input = result(completed(stored));
        when(orders.lockByOrderNo(stored.getOrderNo())).thenReturn(Optional.of(stored));
        when(orders.findByPaymentResultEventId(input.eventId())).thenReturn(Optional.of(order()));

        assertThatThrownBy(() -> apply.execute(EventType.ORDER_PAYMENT_COMPLETED, input))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.CONFLICT));

        assertThat(stored.getStatus()).isEqualTo(OrderStatus.PENDING);
        verifyNoInteractions(inbox, outbox, entityManager);
    }

    @Test
    void anInboxHitWithoutAStoredResultIsNotTreatedAsSuccessfulRedelivery() {
        Order stored = order();
        PaymentResult input = result(completed(stored));
        when(orders.lockByOrderNo(stored.getOrderNo())).thenReturn(Optional.of(stored));
        when(inbox.firstDelivery(input.eventId(), ApplyPaymentResult.CONSUMER_GROUP,
                EventType.ORDER_PAYMENT_COMPLETED)).thenReturn(false);

        assertThatThrownBy(() -> apply.execute(EventType.ORDER_PAYMENT_COMPLETED, input))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.CONFLICT));

        assertThat(stored.getStatus()).isEqualTo(OrderStatus.PENDING);
        verifyNoInteractions(outbox, entityManager);
    }

    @ParameterizedTest
    @MethodSource("invalidEventTypes")
    void rejectsInvalidEventTypesBeforeLookingUpAnOrder(String eventType) {
        PaymentResult input = result(completed(order()));

        assertThatThrownBy(() -> apply.execute(eventType, input))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));

        verifyNoInteractions(orders, inbox, outbox, entityManager);
    }

    @Test
    void rejectsAMissingResultBeforeLookingUpAnOrder() {
        assertThatThrownBy(() -> apply.execute(EventType.ORDER_PAYMENT_COMPLETED, null))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));

        verifyNoInteractions(orders, inbox, outbox, entityManager);
    }

    static Stream<String> invalidEventTypes() {
        return Stream.of(null, "", " ", "x".repeat(61));
    }
}
