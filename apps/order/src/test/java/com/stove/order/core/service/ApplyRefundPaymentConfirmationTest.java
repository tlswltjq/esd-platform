package com.stove.order.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.stove.common.kafka.contract.EventType;
import com.stove.common.kafka.contract.payload.PurchaseRefundedEvent;
import com.stove.common.messaging.inbox.ProcessedEventGuard;
import com.stove.common.messaging.outbox.OutboxRecorder;
import com.stove.order.core.domain.Order;
import com.stove.order.core.domain.OrderRepository;
import com.stove.order.core.domain.Refund;
import com.stove.order.core.domain.RefundAttempt;
import com.stove.order.core.domain.RefundAttemptRepository;
import com.stove.order.core.domain.RefundPaymentConfirmation;
import com.stove.order.core.domain.RefundRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 결정 96: Order가 환불 완료를 확정하면 License가 구매를 끝내도록 사실을 발행한다. */
class ApplyRefundPaymentConfirmationTest {
    static final Instant AT = Instant.parse("2026-11-06T01:00:00.123456Z");
    final OrderRepository orders = mock(OrderRepository.class);
    final RefundRepository refunds = mock(RefundRepository.class);
    final RefundAttemptRepository attempts = mock(RefundAttemptRepository.class);
    final ProcessedEventGuard inbox = mock(ProcessedEventGuard.class);
    final OutboxRecorder outbox = mock(OutboxRecorder.class);
    final EntityManager entityManager = mock(EntityManager.class);
    final Order order = mock(Order.class);
    final Refund refund = mock(Refund.class);
    final RefundAttempt attempt = mock(RefundAttempt.class);
    final String orderNo = UUID.randomUUID().toString();
    final String attemptId = UUID.randomUUID().toString();

    @Test
    @DisplayName("처음 확정한 환불 완료는 License에 구매 환불 완료 사실을 발행한다")
    void firstCompletionPublishesPurchaseRefunded() {
        RefundPaymentConfirmation input = input();
        stub(true);

        service().execute(input);

        ArgumentCaptor<PurchaseRefundedEvent> event = ArgumentCaptor.forClass(PurchaseRefundedEvent.class);
        verify(outbox).record(eq("Order"), eq(orderNo), event.capture());
        PurchaseRefundedEvent refunded = event.getValue();
        assertThat(refunded.refundId()).isEqualTo("refund-1");
        assertThat(refunded.licenseId()).isEqualTo("license-1");
        assertThat(refunded.orderNo()).isEqualTo(orderNo);
        assertThat(refunded.buyerSubject()).isEqualTo("buyer");
        assertThat(refunded.productId()).isEqualTo(7L);
        assertThat(refunded.productCode()).isEqualTo("Game-7");
        assertThat(refunded.paymentId()).isEqualTo("payment-1");
        assertThat(refunded.suspensionRequestEventId()).isEqualTo("suspend-1");
        assertThat(refunded.occurredAt()).isEqualTo(AT);
        assertThat(refunded.partitionKey()).isEqualTo(orderNo);
    }

    @Test
    @DisplayName("이미 확정한 환불 완료의 재전달은 다시 발행하지 않는다")
    void redeliveryDoesNotPublishAgain() {
        RefundPaymentConfirmation input = input();
        stub(false);

        service().execute(input);

        verify(outbox, never()).record(any(), any(), any());
    }

    private void stub(boolean changed) {
        when(orders.lockByOrderNo(orderNo)).thenReturn(Optional.of(order));
        when(refunds.findByOrderNo(orderNo)).thenReturn(Optional.of(refund));
        when(attempts.findByAttemptId(attemptId)).thenReturn(Optional.of(attempt));
        when(refund.confirmPayment(eq(order), eq(attempt), any())).thenReturn(changed);
        when(inbox.firstDelivery(any(), eq(ApplyRefundPaymentConfirmation.CONSUMER_GROUP), eq(EventType.REFUND_PAYMENT_CONFIRMED)))
                .thenReturn(changed);
        when(refund.getRefundId()).thenReturn("refund-1");
        when(refund.getCompletedAt()).thenReturn(AT);
        when(attempt.getLicenseId()).thenReturn("license-1");
        when(attempt.getSuspensionRequestEventId()).thenReturn("suspend-1");
        when(order.getOrderNo()).thenReturn(orderNo);
        when(order.getBuyerSubject()).thenReturn("buyer");
        when(order.getProductId()).thenReturn(7L);
        when(order.getProductCode()).thenReturn("Game-7");
        when(order.getPaymentId()).thenReturn("payment-1");
    }

    private RefundPaymentConfirmation input() {
        return new RefundPaymentConfirmation("confirmed-1", AT, UUID.randomUUID().toString(), attemptId, "execution-1",
                orderNo, "payment-1", "buyer", 1000, "KRW", "PG_QUERY_UNATTRIBUTED", "pg-original", List.of("cancel-1"));
    }

    private ApplyRefundPaymentConfirmation service() {
        return new ApplyRefundPaymentConfirmation(orders, refunds, attempts, inbox, outbox, entityManager);
    }
}
