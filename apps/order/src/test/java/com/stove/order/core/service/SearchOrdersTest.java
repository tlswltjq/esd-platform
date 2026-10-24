package com.stove.order.core.service;

import static com.stove.order.OrderFixtures.order;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.order.core.domain.Order;
import com.stove.order.core.domain.OrderRepository;
import java.util.List;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

@ExtendWith(MockitoExtension.class)
class SearchOrdersTest {
    @Mock private OrderRepository orders;
    @InjectMocks private SearchOrders search;

    @ParameterizedTest
    @CsvSource({"0, 1", "0, 20", "2, 100"})
    void scopesTheRequestedPageToItsBuyerAndRequestsAStableNewestFirstOrder(int page, int size) {
        Order own = order();
        Page<Order> expected = new PageImpl<>(List.of(own));
        when(orders.findByBuyerSubject(eq(own.getBuyerSubject()), any(Pageable.class)))
                .thenReturn(expected);

        assertThat(search.execute(own.getBuyerSubject(), page, size)).isSameAs(expected);

        ArgumentCaptor<Pageable> pagination = ArgumentCaptor.forClass(Pageable.class);
        verify(orders).findByBuyerSubject(eq(own.getBuyerSubject()), pagination.capture());
        Pageable requested = pagination.getValue();
        assertThat(requested.getPageNumber()).isEqualTo(page);
        assertThat(requested.getPageSize()).isEqualTo(size);
        assertThat(requested.getSort().stream().toList())
                .containsExactly(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
        verifyNoMoreInteractions(orders);
    }

    @ParameterizedTest
    @CsvSource({"-1, 20", "0, 0", "0, -1", "0, 101"})
    void rejectsInvalidPaginationBeforeReadingOrders(int page, int size) {
        assertThatThrownBy(() -> search.execute("usr_buyer", page, size))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));

        verifyNoInteractions(orders);
    }
}
