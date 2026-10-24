package com.stove.order.core.service;

import static com.stove.order.OrderFixtures.id;
import static com.stove.order.OrderFixtures.order;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.order.core.domain.Order;
import com.stove.order.core.domain.OrderRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReadOrderTest {
    @Mock private OrderRepository orders;
    @InjectMocks private ReadOrder read;

    @Test
    void readsTheOrderUsingBothItsNumberAndTheAuthenticatedBuyer() {
        Order own = order();
        when(orders.findByOrderNoAndBuyerSubject(own.getOrderNo(), own.getBuyerSubject()))
                .thenReturn(Optional.of(own));

        assertThat(read.execute(own.getOrderNo(), own.getBuyerSubject())).isSameAs(own);

        verify(orders).findByOrderNoAndBuyerSubject(own.getOrderNo(), own.getBuyerSubject());
        verifyNoMoreInteractions(orders);
    }

    @Test
    void doesNotFallBackToAnUnscopedLookupForAnotherBuyer() {
        Order anotherBuyersOrder = order();
        String buyer = "usr_other";
        when(orders.findByOrderNoAndBuyerSubject(anotherBuyersOrder.getOrderNo(), buyer))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> read.execute(anotherBuyersOrder.getOrderNo(), buyer))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));

        verify(orders).findByOrderNoAndBuyerSubject(anotherBuyersOrder.getOrderNo(), buyer);
        verifyNoMoreInteractions(orders);
    }

    @Test
    void reportsAMissingOrderWithoutSearchingOtherBuyers() {
        String orderNo = id();
        when(orders.findByOrderNoAndBuyerSubject(orderNo, "usr_buyer"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> read.execute(orderNo, "usr_buyer"))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));

        verify(orders).findByOrderNoAndBuyerSubject(orderNo, "usr_buyer");
        verifyNoMoreInteractions(orders);
    }
}
