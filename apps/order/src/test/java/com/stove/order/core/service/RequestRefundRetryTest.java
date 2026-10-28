package com.stove.order.core.service;

import static com.stove.order.OrderFixtures.id;
import static com.stove.order.OrderFixtures.order;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.stove.common.messaging.outbox.OutboxRecorder;
import com.stove.common.web.error.BusinessException;
import com.stove.order.core.domain.Order;
import com.stove.order.core.domain.OrderRepository;
import com.stove.order.core.domain.RefundRepository;
import com.stove.order.core.domain.RefundAttemptRepository;
import com.stove.order.core.domain.RefundRetryRequest;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RequestRefundRetryTest {
    @Mock OrderRepository orders;
    @Mock RefundRepository refunds;
    @Mock RefundAttemptRepository attempts;
    @Mock OutboxRecorder outbox;
    @Mock EntityManager entityManager;
    @Mock Clock clock;
    @Test void anotherBuyerCannotReachTheRefundHistoryOrAuthorizeANewSuspension() {
        Order order = order(); when(orders.lockByOrderNo(order.getOrderNo())).thenReturn(Optional.of(order));
        RequestRefundRetry service = new RequestRefundRetry(orders, refunds, attempts, outbox, entityManager, clock);
        assertThatThrownBy(() -> service.execute(new RefundRetryRequest("another-buyer", order.getOrderNo(), id()))).isInstanceOf(BusinessException.class);
        verifyNoInteractions(refunds, attempts, outbox, entityManager, clock);
    }
}
