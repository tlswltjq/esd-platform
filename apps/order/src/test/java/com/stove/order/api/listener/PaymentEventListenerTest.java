package com.stove.order.api.listener;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.stove.common.event.EventType;
import com.stove.common.event.Topics;
import com.stove.common.event.payload.PaymentCancelledEvent;
import com.stove.common.event.payload.PaymentCompletedEvent;
import com.stove.common.test.EventRecords;
import com.stove.order.core.service.OrderCommandService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * 주문 상태를 결제 결과에 맞추는 리스너.
 *
 * <p>order 는 {@code OrderCreated}/{@code OrderCanceled} <b>짝</b>을 소유한 세 모듈 중 하나인데
 * (docs/event-ordering.md 2절) 지금까지 리스너 테스트가 없었다.
 *
 * <p>검증의 핵심은 다른 리스너와 같다 — <b>예외를 밖으로 내보내는가.</b>
 * 스프링 카프카는 정상 리턴을 처리 성공으로 보고 오프셋을 커밋하므로,
 * 여기서 예외를 삼키면 재시도 대상 자체가 사라진다(docs/kafka-consumer-retry.md).
 */
class PaymentEventListenerTest {

    private final OrderCommandService orderCommandService = mock(OrderCommandService.class);
    private final PaymentEventListener listener =
            new PaymentEventListener(orderCommandService, EventRecords.OBJECT_MAPPER);

    private static final PaymentCompletedEvent COMPLETED =
            PaymentCompletedEvent.of(1L, "ORD-1", 42L, 30_000L, "CARD", java.util.List.of());
    private static final PaymentCancelledEvent CANCELLED =
            PaymentCancelledEvent.of(1L, "ORD-1", 42L, 30_000L, "USER_REFUND");

    @Test
    @DisplayName("결제 완료는 주문 확정으로 이어진다")
    void completedConfirmsOrder() {
        listener.onPaymentEvent(EventRecords.of(Topics.PAYMENT, COMPLETED));

        verify(orderCommandService).confirmPaid(
                anyString(), eq(EventType.PAYMENT_COMPLETED), eq("ORD-1"));
    }

    @Test
    @DisplayName("결제 취소는 주문 취소로 이어지고 사유가 함께 넘어간다")
    void cancelledCancelsOrder() {
        listener.onPaymentEvent(EventRecords.of(Topics.PAYMENT, CANCELLED));

        verify(orderCommandService).confirmCanceled(
                anyString(), eq(EventType.PAYMENT_CANCELLED), eq("ORD-1"), eq("USER_REFUND"));
    }

    @Test
    @DisplayName("관심 없는 eventType 은 아무 일도 하지 않는다")
    void unrelatedEventTypeIsIgnored() {
        listener.onPaymentEvent(EventRecords.ofUnrelatedType(Topics.PAYMENT));

        verifyNoInteractions(orderCommandService);
    }

    @Test
    @DisplayName("확정 중 일시 장애는 예외로 전파된다 — 컨테이너 재시도의 전제조건")
    void propagatesTransientFailureOnConfirm() {
        doThrow(new DataAccessResourceFailureException("connection lost"))
                .when(orderCommandService).confirmPaid(anyString(), anyString(), anyString());

        assertThatThrownBy(() -> listener.onPaymentEvent(EventRecords.of(Topics.PAYMENT, COMPLETED)))
                .isInstanceOf(DataAccessResourceFailureException.class);
    }

    @Test
    @DisplayName("취소 경로의 예외도 그대로 전파된다 — 두 분기의 정책이 같다")
    void propagatesTransientFailureOnCancel() {
        doThrow(new DataAccessResourceFailureException("connection lost"))
                .when(orderCommandService)
                .confirmCanceled(anyString(), anyString(), anyString(), anyString());

        assertThatThrownBy(() -> listener.onPaymentEvent(EventRecords.of(Topics.PAYMENT, CANCELLED)))
                .isInstanceOf(DataAccessResourceFailureException.class);
    }
}
