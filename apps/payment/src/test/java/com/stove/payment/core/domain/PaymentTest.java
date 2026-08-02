package com.stove.payment.core.domain;

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
    @DisplayName("취소는 착수와 확정 두 걸음으로 나뉜다 — 사이에 PG 환불이 들어간다")
    void cancelIsTwoPhased() {
        Payment payment = paidPayment();

        assertThat(payment.beginCancel("USER_REFUND")).isTrue();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELING);

        payment.completeCancel();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELED);
    }

    @Test
    @DisplayName("확정이 끝난 건은 다시 착수하지 않는다")
    void cancelIsIdempotent() {
        Payment payment = paidPayment();
        payment.beginCancel("USER_REFUND");
        payment.completeCancel();

        assertThat(payment.beginCancel("USER_REFUND")).isFalse();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELED);
    }

    @Test
    @DisplayName("착수만 된 건은 다시 착수할 수 있다 — 확정이 깨졌을 때 재시도 경로")
    void cancelingCanBeRetried() {
        Payment payment = paidPayment();
        payment.beginCancel("USER_REFUND");

        assertThat(payment.beginCancel("USER_REFUND")).isTrue();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELING);
    }

    @Test
    @DisplayName("승인 전에는 취소를 착수할 수 없다")
    void cannotCancelBeforeApproval() {
        Payment payment = readyPayment();
        payment.prepare("PG-TX-1", "CARD");

        assertThatThrownBy(() -> payment.beginCancel("USER_REFUND"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("착수 없이 확정할 수 없다")
    void cannotCompleteWithoutBegin() {
        Payment payment = paidPayment();

        assertThatThrownBy(payment::completeCancel).isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("[D-008] 다른 멱등키의 두 번째 승인은 중복이 아니라 사고다")
    void secondApprovalWithDifferentKeyIsRejected() {
        Payment payment = paidPayment();

        // 같은 키면 재전송(false), 다른 키면 PG 오류/위변조 → 조용히 넘기지 않는다
        assertThatThrownBy(() -> payment.approve("PG-TX-1", 39000L, "KEY-2"))
                .isInstanceOf(BusinessException.class);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PAID);
    }

    @Test
    @DisplayName("[D-007] 보상 경로는 취소 가능 여부를 예외 대신 값으로 묻는다")
    void cancelableAnswersWithValue() {
        Payment ready = readyPayment();
        assertThat(ready.cancelable()).isFalse();

        ready.prepare("PG-TX-1", "CARD");
        assertThat(ready.cancelable()).isFalse();

        Payment paid = paidPayment();
        assertThat(paid.cancelable()).isTrue();

        paid.beginCancel("USER_REFUND");
        assertThat(paid.cancelable()).isTrue();

        paid.completeCancel();
        assertThat(paid.cancelable()).isTrue();
    }

    private Payment paidPayment() {
        Payment payment = readyPayment();
        payment.prepare("PG-TX-1", "CARD");
        payment.approve("PG-TX-1", 39000L, "KEY-1");
        return payment;
    }
}
