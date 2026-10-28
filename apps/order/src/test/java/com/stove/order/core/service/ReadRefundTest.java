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
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ReadRefundTest {
    private final OrderRepository orders = mock(OrderRepository.class);
    private final RefundRepository refunds = mock(RefundRepository.class);
    private final ReadRefund read = new ReadRefund(orders, refunds);

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
}
