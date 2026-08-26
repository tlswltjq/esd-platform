package com.stove.payment.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stove.common.core.error.BusinessException;
import com.stove.common.core.error.ErrorCode;
import com.stove.common.event.EventType;
import com.stove.common.event.payload.OrderLine;
import com.stove.common.testcontainers.InfraContainers;
import com.stove.payment.core.domain.Payment;
import com.stove.payment.core.domain.PaymentStatus;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * 주문번호로 결제 한 건 조회 — {@code GET /api/v1/payments/{orderNo}} 가 타는 경로.
 *
 * <p>이 메서드는 그동안 배포된 스택 위 인수 테스트에서만 실행됐다. 인수 테스트는 기본 빌드
 * 밖이라 main 에 넣을 때만 돌고, 그래서 <b>PR 단계에서는 이 경로를 지키는 것이 없었다.</b>
 *
 * <p>조회에서 갈리는 것은 둘이다. 있는 주문을 그대로 돌려주는가, 그리고 없는 주문에
 * <b>빈 값이 아니라 예외</b>를 주는가. 후자를 놓치면 부르는 쪽이 "결제가 없다"와
 * "아직 결제 전이다"를 구분하지 못한다 — 사례 4 에서 승인이 조용히 삼켜진 것과 같은 모양이다.
 */
@SpringBootTest(properties = "stove.outbox.relay-enabled=false")
@Import({InfraContainers.MySql.class, InfraContainers.Kafka.class})
class PaymentLookupTest {

    @Autowired
    PaymentService paymentService;

    private String readyPayment(long amount) {
        String orderNo = "ORD-" + UUID.randomUUID();
        paymentService.createReady(UUID.randomUUID().toString(), EventType.ORDER_CREATED,
                orderNo, 42L, amount, "KRW",
                List.of(new OrderLine(1L, "게임 A", 1001L, amount, 1)));
        return orderNo;
    }

    @Test
    @DisplayName("주문번호로 찾으면 그 주문의 결제가 돌아온다")
    void findsByOrderNo() {
        String orderNo = readyPayment(39_000L);

        Payment payment = paymentService.getPayment(orderNo);

        assertThat(payment.getOrderNo()).isEqualTo(orderNo);
        assertThat(payment.getAmount()).isEqualTo(39_000L);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.READY);
    }

    @Test
    @DisplayName("다른 주문의 결제가 섞여 나오지 않는다")
    void doesNotLeakOtherOrders() {
        String mine = readyPayment(10_000L);
        String other = readyPayment(20_000L);

        assertThat(paymentService.getPayment(mine).getOrderNo()).isEqualTo(mine);
        assertThat(paymentService.getPayment(mine).getAmount()).isEqualTo(10_000L);
        assertThat(paymentService.getPayment(other).getAmount()).isEqualTo(20_000L);
    }

    @Test
    @DisplayName("없는 주문번호는 빈 값이 아니라 예외다 — 미결제와 구분되어야 한다")
    void unknownOrderNoThrows() {
        String never = "ORD-" + UUID.randomUUID();

        assertThatThrownBy(() -> paymentService.getPayment(never))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.PAYMENT_NOT_FOUND);
    }
}
