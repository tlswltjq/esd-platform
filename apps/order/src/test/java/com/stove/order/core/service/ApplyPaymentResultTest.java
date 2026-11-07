package com.stove.order.core.service;

import static com.stove.order.OrderFixtures.completed;
import static com.stove.order.OrderFixtures.failed;
import static com.stove.order.OrderFixtures.id;
import static com.stove.order.OrderFixtures.order;
import static com.stove.order.OrderFixtures.result;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
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
import com.stove.order.core.domain.ExpiredReviewClosure;
import com.stove.order.core.domain.ExpiredReviewClosureRepository;
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
    @Mock private ExpiredReviewClosureRepository keptClosures;
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

    /** 결정 115: 운영자 종결 실패가 주문 자체 만료와 엇갈려 도착하면 만료를 유지하고, 결과 식별을 따로 남겨 재전달을 대조한다. */
    @Test
    void aReviewClosureThatCrossesTheOrdersOwnExpiryKeepsTheExpiryAndRecordsTheResultIdentity() {
        Order stored = expired();
        PaymentResult input = failure(stored, "REVIEW_CLOSED");
        when(orders.lockByOrderNo(stored.getOrderNo())).thenReturn(Optional.of(stored));
        when(inbox.firstDelivery(input.eventId(), ApplyPaymentResult.CONSUMER_GROUP, EventType.ORDER_PAYMENT_FAILED)).thenReturn(true);

        apply.execute(EventType.ORDER_PAYMENT_FAILED, input);

        assertThat(stored.getStatus()).isEqualTo(OrderStatus.EXPIRED);
        assertThat(stored.paymentResult()).isNull();
        assertThat(stored.getFailureReasonCode()).isNull();
        ArgumentCaptor<ExpiredReviewClosure> kept = ArgumentCaptor.forClass(ExpiredReviewClosure.class);
        verify(keptClosures).save(kept.capture());
        assertThat(kept.getValue().getOrderNo()).isEqualTo(stored.getOrderNo());
        assertThat(kept.getValue().getEventId()).isEqualTo(input.eventId());
        assertThat(kept.getValue().getPaymentId()).isEqualTo(input.paymentId());
        assertThat(kept.getValue().getReasonCode()).isEqualTo("REVIEW_CLOSED");
        assertThat(kept.getValue().getReason()).isEqualTo(input.reason());
        assertThat(kept.getValue().getOccurredAt()).isEqualTo(input.occurredAt());
        verify(entityManager).flush();
        verifyNoInteractions(outbox);
    }

    @Test
    void aRedeliveryOfTheSameRecordedReviewClosureIsANoOp() {
        Order stored = expired();
        PaymentResult input = failure(stored, "REVIEW_CLOSED");
        ExpiredReviewClosure recorded = ExpiredReviewClosure.record(stored, input, stored.getExpiresAt().plusSeconds(1));
        when(orders.lockByOrderNo(stored.getOrderNo())).thenReturn(Optional.of(stored));
        when(keptClosures.findByEventId(input.eventId())).thenReturn(Optional.of(recorded));
        when(keptClosures.findByOrderNo(stored.getOrderNo())).thenReturn(Optional.of(recorded));

        apply.execute(EventType.ORDER_PAYMENT_FAILED, input);

        assertThat(stored.getStatus()).isEqualTo(OrderStatus.EXPIRED);
        verify(keptClosures, never()).save(any(ExpiredReviewClosure.class));
        verifyNoInteractions(inbox, outbox);
    }

    @Test
    void aRedeliveryWithChangedContentConflictsWithTheRecordedReviewClosure() {
        Order stored = expired();
        PaymentResult input = failure(stored, "REVIEW_CLOSED");
        ExpiredReviewClosure recorded = ExpiredReviewClosure.record(stored, input, stored.getExpiresAt().plusSeconds(1));
        when(orders.lockByOrderNo(stored.getOrderNo())).thenReturn(Optional.of(stored));
        when(keptClosures.findByEventId(input.eventId())).thenReturn(Optional.of(recorded));
        when(keptClosures.findByOrderNo(stored.getOrderNo())).thenReturn(Optional.of(recorded));
        PaymentResult anotherEvent = failure(stored, "REVIEW_CLOSED");
        when(keptClosures.findByEventId(anotherEvent.eventId())).thenReturn(Optional.empty());
        for (PaymentResult changed : new PaymentResult[]{
                new PaymentResult(input.eventId(), input.occurredAt(), input.orderNo(), input.requestEventId(), id(), input.buyerSubject(),
                        input.amount(), input.currency(), OrderStatus.FAILED, "REVIEW_CLOSED", input.reason()),
                new PaymentResult(input.eventId(), input.occurredAt(), input.orderNo(), input.requestEventId(), input.paymentId(), input.buyerSubject(),
                        input.amount(), input.currency(), OrderStatus.FAILED, "REVIEW_CLOSED", "a different reason"),
                new PaymentResult(input.eventId(), input.occurredAt().plusSeconds(1), input.orderNo(), input.requestEventId(), input.paymentId(),
                        input.buyerSubject(), input.amount(), input.currency(), OrderStatus.FAILED, "REVIEW_CLOSED", input.reason())})
            assertThatThrownBy(() -> apply.execute(EventType.ORDER_PAYMENT_FAILED, changed))
                    .isInstanceOfSatisfying(BusinessException.class, failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.CONFLICT));
        assertThatThrownBy(() -> apply.execute(EventType.ORDER_PAYMENT_FAILED, anotherEvent))
                .isInstanceOfSatisfying(BusinessException.class, failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.CONFLICT));
        assertThat(stored.getStatus()).isEqualTo(OrderStatus.EXPIRED);
        verify(keptClosures, never()).save(any(ExpiredReviewClosure.class));
        verifyNoInteractions(inbox, outbox);
    }

    @Test
    void aResultEventIdAlreadyKeptForAnotherOrderIsRejectedBeforeTheInbox() {
        Order first = expired();
        Order second = expired();
        PaymentResult kept = failure(first, "REVIEW_CLOSED");
        ExpiredReviewClosure recorded = ExpiredReviewClosure.record(first, kept, first.getExpiresAt().plusSeconds(1));
        PaymentResult reused = new PaymentResult(kept.eventId(), kept.occurredAt(), second.getOrderNo(), second.getRequestEventId(), kept.paymentId(),
                second.getBuyerSubject(), second.getAmount(), second.getCurrency(), OrderStatus.FAILED, "REVIEW_CLOSED", kept.reason());
        Order pending = order();
        PaymentResult reusedAsAPayment = new PaymentResult(kept.eventId(), kept.occurredAt(), pending.getOrderNo(), pending.getRequestEventId(), kept.paymentId(),
                pending.getBuyerSubject(), pending.getAmount(), pending.getCurrency(), OrderStatus.PAID, null, null);
        when(orders.lockByOrderNo(second.getOrderNo())).thenReturn(Optional.of(second));
        when(orders.lockByOrderNo(pending.getOrderNo())).thenReturn(Optional.of(pending));
        when(keptClosures.findByEventId(kept.eventId())).thenReturn(Optional.of(recorded));

        assertThatThrownBy(() -> apply.execute(EventType.ORDER_PAYMENT_FAILED, reused))
                .isInstanceOfSatisfying(BusinessException.class, failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.CONFLICT));
        assertThatThrownBy(() -> apply.execute(EventType.ORDER_PAYMENT_COMPLETED, reusedAsAPayment))
                .isInstanceOfSatisfying(BusinessException.class, failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.CONFLICT));
        assertThat(second.getStatus()).isEqualTo(OrderStatus.EXPIRED);
        assertThat(pending.getStatus()).isEqualTo(OrderStatus.PENDING);
        verify(keptClosures, never()).save(any(ExpiredReviewClosure.class));
        verifyNoInteractions(inbox, outbox);
    }

    @Test
    void anInboxHitWithoutARecordedReviewClosureIsNotANormalDuplicate() {
        Order stored = expired();
        PaymentResult input = failure(stored, "REVIEW_CLOSED");
        when(orders.lockByOrderNo(stored.getOrderNo())).thenReturn(Optional.of(stored));
        when(inbox.firstDelivery(input.eventId(), ApplyPaymentResult.CONSUMER_GROUP, EventType.ORDER_PAYMENT_FAILED)).thenReturn(false);

        assertThatThrownBy(() -> apply.execute(EventType.ORDER_PAYMENT_FAILED, input))
                .isInstanceOfSatisfying(BusinessException.class, failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.CONFLICT));

        assertThat(stored.getStatus()).isEqualTo(OrderStatus.EXPIRED);
        verify(keptClosures, never()).save(any(ExpiredReviewClosure.class));
        verifyNoInteractions(outbox);
    }

    @Test
    void everyOtherResultForAnExpiredOrderStillConflicts() {
        Order stored = expired();
        for (PaymentResult input : new PaymentResult[]{failure(stored, "DECLINED"), result(completed(stored))}) {
            String type = input.status() == OrderStatus.PAID ? EventType.ORDER_PAYMENT_COMPLETED : EventType.ORDER_PAYMENT_FAILED;
            when(orders.lockByOrderNo(stored.getOrderNo())).thenReturn(Optional.of(stored));
            when(inbox.firstDelivery(input.eventId(), ApplyPaymentResult.CONSUMER_GROUP, type)).thenReturn(true);

            assertThatThrownBy(() -> apply.execute(type, input))
                    .isInstanceOfSatisfying(BusinessException.class,
                            failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.CONFLICT));
            assertThat(stored.getStatus()).isEqualTo(OrderStatus.EXPIRED);
        }
        PaymentResult mismatched = new PaymentResult(id(), stored.getExpiresAt(), stored.getOrderNo(), stored.getRequestEventId(), id(),
                "other-buyer", stored.getAmount(), stored.getCurrency(), OrderStatus.FAILED, "REVIEW_CLOSED", "closed");
        assertThatThrownBy(() -> apply.execute(EventType.ORDER_PAYMENT_FAILED, mismatched))
                .isInstanceOfSatisfying(BusinessException.class, failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.CONFLICT));
        verifyNoInteractions(outbox);
    }

    @Test
    void aReviewClosureBeforeExpiryFailsThePendingOrderAsAnyFinalFailure() {
        Order stored = order();
        PaymentResult input = failure(stored, "REVIEW_CLOSED");
        when(orders.lockByOrderNo(stored.getOrderNo())).thenReturn(Optional.of(stored));
        when(inbox.firstDelivery(input.eventId(), ApplyPaymentResult.CONSUMER_GROUP, EventType.ORDER_PAYMENT_FAILED)).thenReturn(true);

        apply.execute(EventType.ORDER_PAYMENT_FAILED, input);

        assertThat(stored.getStatus()).isEqualTo(OrderStatus.FAILED);
        assertThat(stored.paymentResult()).isEqualTo(input);
        verifyNoInteractions(outbox);
    }

    private static Order expired() {
        Order stored = order();
        assertThat(stored.expire(stored.getExpiresAt())).isTrue();
        return stored;
    }

    private static PaymentResult failure(Order stored, String reasonCode) {
        return new PaymentResult(id(), stored.getExpiresAt(), stored.getOrderNo(), stored.getRequestEventId(), id(),
                stored.getBuyerSubject(), stored.getAmount(), stored.getCurrency(), OrderStatus.FAILED, reasonCode, "Payment approval review closed");
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
