package com.stove.settlement.api.listener;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.stove.common.event.EventType;
import com.stove.common.event.Topics;
import com.stove.common.event.payload.OrderLine;
import com.stove.common.event.payload.PaymentCancelledEvent;
import com.stove.common.event.payload.PaymentCompletedEvent;
import com.stove.common.test.EventRecords;
import com.stove.settlement.core.service.SettlementRecordService;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * 정산 원장의 입구. <b>금전이 흐르는 경로</b>라 실패 처리 정책이 특히 중요하다.
 *
 * <p>여기서 예외를 삼키면 오프셋이 커밋되고 그 매출/환불은 원장에 영영 들어오지 않는다.
 * 정산은 월 단위로 마감되므로 <b>누락이 드러나는 시점이 한참 뒤</b>다 —
 * D-001 이 정확히 그런 종류의 결함이었다.
 */
class PaymentEventListenerTest {

    private final SettlementRecordService settlementRecordService = mock(SettlementRecordService.class);
    private final PaymentEventListener listener =
            new PaymentEventListener(settlementRecordService, EventRecords.OBJECT_MAPPER);

    private static final List<OrderLine> LINES =
            List.of(new OrderLine(1L, "게임 A", 1001L, 30_000L, 1));
    private static final PaymentCompletedEvent COMPLETED =
            PaymentCompletedEvent.of(1L, "ORD-1", 42L, 30_000L, "CARD", LINES);
    private static final PaymentCancelledEvent CANCELLED =
            PaymentCancelledEvent.of(1L, "ORD-1", 42L, 30_000L, "USER_REFUND");

    @Test
    @DisplayName("결제 완료는 매출로 집계된다 — 판매 라인이 그대로 넘어간다")
    void completedRecordsSale() {
        listener.onPaymentEvent(EventRecords.of(Topics.PAYMENT, COMPLETED));

        verify(settlementRecordService).recordSale(
                anyString(), eq(EventType.PAYMENT_COMPLETED), eq("ORD-1"), eq(LINES));
    }

    @Test
    @DisplayName("결제 취소는 환불로 역산된다")
    void cancelledRecordsRefund() {
        listener.onPaymentEvent(EventRecords.of(Topics.PAYMENT, CANCELLED));

        verify(settlementRecordService).recordRefund(
                anyString(), eq(EventType.PAYMENT_CANCELLED), eq("ORD-1"));
    }

    @Test
    @DisplayName("관심 없는 eventType 은 원장을 건드리지 않는다")
    void unrelatedEventTypeIsIgnored() {
        listener.onPaymentEvent(EventRecords.ofUnrelatedType(Topics.PAYMENT));

        verifyNoInteractions(settlementRecordService);
    }

    @Test
    @DisplayName("매출 집계 중 일시 장애는 예외로 전파된다 — 삼키면 매출이 원장에서 사라진다")
    void propagatesFailureOnSale() {
        doThrow(new DataAccessResourceFailureException("connection lost"))
                .when(settlementRecordService).recordSale(anyString(), anyString(), anyString(), any());

        assertThatThrownBy(() -> listener.onPaymentEvent(EventRecords.of(Topics.PAYMENT, COMPLETED)))
                .isInstanceOf(DataAccessResourceFailureException.class);
    }

    @Test
    @DisplayName("환불 역산의 예외도 그대로 전파된다 — 두 분기의 정책이 같다")
    void propagatesFailureOnRefund() {
        doThrow(new DataAccessResourceFailureException("connection lost"))
                .when(settlementRecordService).recordRefund(anyString(), anyString(), anyString());

        assertThatThrownBy(() -> listener.onPaymentEvent(EventRecords.of(Topics.PAYMENT, CANCELLED)))
                .isInstanceOf(DataAccessResourceFailureException.class);
    }
}
