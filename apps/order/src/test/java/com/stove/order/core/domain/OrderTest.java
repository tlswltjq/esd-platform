package com.stove.order.core.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stove.common.core.error.BusinessException;
import com.stove.common.event.payload.OrderLine;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OrderTest {

    private Order sampleOrder() {
        return Order.create("ORD20260101ABCDE12345", 1L, "KRW", List.of(
                new OrderLine(1L, "로스트아크 디럭스 패키지", 1L, 39000L, 1),
                new OrderLine(2L, "인디 플랫포머 데모+", 1001L, 12000L, 2)));
    }

    @Test
    @DisplayName("주문 금액은 항목 합계로만 계산된다")
    void totalAmountFromLines() {
        assertThat(sampleOrder().getTotalAmount()).isEqualTo(39000L + 12000L * 2);
    }

    @Test
    @DisplayName("결제 완료 이벤트가 중복 수신돼도 상태는 한 번만 전이된다")
    void markPaidIsIdempotent() {
        Order order = sampleOrder();
        order.markPaid();
        order.markPaid();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(order.getPaidAt()).isNotNull();
    }

    @Test
    @DisplayName("결제 실패는 주문을 FAILED 로 끝내고 사유를 남긴다")
    void markFailedEndsOrder() {
        Order order = sampleOrder();
        order.markFailed("REJECT_CARD_COMPANY:카드사 거절");

        assertThat(order.getStatus()).isEqualTo(OrderStatus.FAILED);
        assertThat(order.getFailedAt()).isNotNull();
        assertThat(order.getFailReason()).isEqualTo("REJECT_CARD_COMPANY:카드사 거절");
    }

    @Test
    @DisplayName("결제 실패 이벤트가 중복 수신돼도 상태는 한 번만 전이된다")
    void markFailedIsIdempotent() {
        Order order = sampleOrder();
        order.markFailed("REJECT_CARD_COMPANY:카드사 거절");
        order.markFailed("REJECT_CARD_COMPANY:카드사 거절");

        assertThat(order.getStatus()).isEqualTo(OrderStatus.FAILED);
    }

    @Test
    @DisplayName("이미 결제된 주문은 실패로 뒤집을 수 없다")
    void cannotFailPaidOrder() {
        Order order = sampleOrder();
        order.markPaid();

        assertThatThrownBy(() -> order.markFailed("REJECT_CARD_COMPANY:카드사 거절"))
                .isInstanceOf(BusinessException.class);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
    }

    @Test
    @DisplayName("실패로 끝난 주문은 취소할 수 없다 — 되돌릴 돈이 없다")
    void cannotCancelFailedOrder() {
        Order order = sampleOrder();
        order.markFailed("REJECT_CARD_COMPANY:카드사 거절");

        assertThatThrownBy(() -> order.cancel("USER_REFUND"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("본인 주문이 아니면 조회할 수 없다")
    void requireOwner() {
        assertThatThrownBy(() -> sampleOrder().requireOwner(999L))
                .isInstanceOf(BusinessException.class);
    }
}
