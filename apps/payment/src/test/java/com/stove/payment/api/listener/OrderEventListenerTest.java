package com.stove.payment.api.listener;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.stove.common.event.EventType;
import com.stove.common.event.Topics;
import com.stove.common.event.payload.OrderCreatedEvent;
import com.stove.common.event.payload.OrderLine;
import com.stove.common.test.EventRecords;
import com.stove.payment.core.service.PaymentService;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * 주문 생성 → 결제 준비. 커머스 흐름이 payment 로 넘어오는 지점이다.
 *
 * <p>통화가 리스너에서 {@code "KRW"} 로 고정된다는 점을 함께 고정한다 —
 * 이벤트에 통화가 없어 어댑터가 채워 넣는 값이라, 조용히 바뀌면 금액 해석이 통째로 달라진다.
 */
class OrderEventListenerTest {

    private final PaymentService paymentService = mock(PaymentService.class);
    private final OrderEventListener listener =
            new OrderEventListener(paymentService, EventRecords.OBJECT_MAPPER);

    private static final List<OrderLine> LINES =
            List.of(new OrderLine(1L, "게임 A", 1001L, 30_000L, 1));
    private static final OrderCreatedEvent CREATED =
            OrderCreatedEvent.of("ORD-1", 42L, 30_000L, LINES);

    @Test
    @DisplayName("주문 생성은 결제 준비로 이어진다 — 금액·회원·라인이 그대로 넘어간다")
    void orderCreatedPreparesPayment() {
        listener.onOrderEvent(EventRecords.of(Topics.ORDER, CREATED));

        verify(paymentService).createReady(
                anyString(), eq(EventType.ORDER_CREATED), eq("ORD-1"),
                eq(42L), eq(30_000L), eq("KRW"), eq(LINES));
    }

    @Test
    @DisplayName("주문 취소는 이 리스너의 관심사가 아니다")
    void unrelatedEventTypeIsIgnored() {
        listener.onOrderEvent(EventRecords.ofUnrelatedType(Topics.ORDER));

        verifyNoInteractions(paymentService);
    }

    @Test
    @DisplayName("결제 준비 중 일시 장애는 예외로 전파된다 — 삼키면 결제 자체가 사라진다")
    void propagatesTransientFailure() {
        doThrow(new DataAccessResourceFailureException("connection lost"))
                .when(paymentService).createReady(anyString(), anyString(), anyString(),
                        anyLong(), anyLong(), anyString(), any());

        assertThatThrownBy(() -> listener.onOrderEvent(EventRecords.of(Topics.ORDER, CREATED)))
                .isInstanceOf(DataAccessResourceFailureException.class);
    }
}
