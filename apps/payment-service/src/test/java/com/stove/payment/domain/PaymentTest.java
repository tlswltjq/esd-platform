package com.stove.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stove.common.core.error.BusinessException;
import com.stove.common.event.payload.OrderLine;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PaymentTest {

    private Payment readyPayment() {
        return Payment.ready("ORD20260101ABCDE12345", 1L, 39000L, "KRW",
                List.of(new OrderLine(1L, "로스트아크 디럭스 패키지", 1L, 39000L, 1)));
    }

    @Test
    @DisplayName("승인 금액이 사전등록 금액과 다르면 승인되지 않는다")
    void rejectAmountMismatch() {
        Payment payment = readyPayment();
        payment.prepare("PG-TX-1", "CARD");

        assertThatThrownBy(() -> payment.approve("PG-TX-1", 1000L, "KEY-1"))
                .isInstanceOf(BusinessException.class);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    @DisplayName("같은 콜백이 두 번 와도 승인은 한 번만 확정된다")
    void idempotentCallback() {
        Payment payment = readyPayment();
        payment.prepare("PG-TX-1", "CARD");

        assertThat(payment.approve("PG-TX-1", 39000L, "KEY-1")).isTrue();
        assertThat(payment.approve("PG-TX-1", 39000L, "KEY-1")).isFalse();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PAID);
    }

    @Test
    @DisplayName("승인 완료 건만 취소할 수 있고, 취소는 멱등하다")
    void cancelIsIdempotent() {
        Payment payment = readyPayment();
        payment.prepare("PG-TX-1", "CARD");
        payment.approve("PG-TX-1", 39000L, "KEY-1");

        assertThat(payment.cancel("USER_REFUND")).isTrue();
        assertThat(payment.cancel("USER_REFUND")).isFalse();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELED);
    }
}
