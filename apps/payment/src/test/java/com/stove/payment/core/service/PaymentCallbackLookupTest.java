package com.stove.payment.core.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.stove.common.event.EventType;
import com.stove.common.event.payload.OrderLine;
import com.stove.common.messaging.outbox.OutboxEventRepository;
import com.stove.common.test.InfraContainers;
import com.stove.payment.core.domain.Payment;
import com.stove.payment.core.domain.PaymentPreparation;
import com.stove.payment.core.domain.PaymentRepository;
import com.stove.payment.core.domain.PaymentStatus;
import com.stove.payment.core.domain.PgApproval;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * 콜백이 <b>어느 결제 건에 매칭되는가</b>. 금액 대조보다 한 단계 앞선 질문이다 —
 * 매칭이 틀리면 그 다음의 모든 검증이 엉뚱한 대상 위에서 통과한다.
 *
 * <p>{@code handleApproval} 은 멱등키로 먼저 찾고, 없으면 주문번호로 찾는다.
 * 멱등키는 PG 가 만들어 주는 값이라 우리가 유일성을 보장할 수 없다는 점이 핵심이다.
 */
@SpringBootTest(properties = "stove.outbox.relay-enabled=false")
@Import({InfraContainers.MySql.class, InfraContainers.Kafka.class})
class PaymentCallbackLookupTest {

    @Autowired
    PaymentService paymentService;
    @Autowired
    PaymentRepository paymentRepository;
    @Autowired
    OutboxEventRepository outboxEventRepository;

    private String preparedOrder(long amount) {
        String orderNo = "ORD-" + UUID.randomUUID();
        paymentService.createReady(UUID.randomUUID().toString(), EventType.ORDER_CREATED,
                orderNo, 42L, amount, "KRW",
                List.of(new OrderLine(1L, "게임 A", 1001L, amount, 1)));
        paymentService.prepare(orderNo, "CARD");
        return orderNo;
    }

    private String pgTxIdOf(String orderNo) {
        return paymentRepository.findByOrderNo(orderNo).orElseThrow().getPgTxId();
    }

    private Payment reload(String orderNo) {
        return paymentRepository.findByOrderNo(orderNo).orElseThrow();
    }

    @Test
    @DisplayName("콜백은 주문번호에 해당하는 결제를 승인한다")
    void approvesPaymentOfGivenOrder() {
        String orderNo = preparedOrder(30_000L);

        paymentService.handleApproval(new PgApproval(orderNo, pgTxIdOf(orderNo), 30_000L,
                "PGKEY-" + UUID.randomUUID()));

        assertThat(reload(orderNo).getStatus()).isEqualTo(PaymentStatus.PAID);
    }

    @Test
    @DisplayName("승인된 결제에는 멱등키가 남아 중복 콜백을 식별할 수 있다")
    void storesIdempotencyKeyOnApproval() {
        String orderNo = preparedOrder(30_000L);
        String key = "PGKEY-" + UUID.randomUUID();

        paymentService.handleApproval(new PgApproval(orderNo, pgTxIdOf(orderNo), 30_000L, key));

        assertThat(reload(orderNo).getIdempotencyKey()).isEqualTo(key);
        assertThat(paymentRepository.findByIdempotencyKey(key)).isPresent();
    }

    @Test
    @Tag("known-defect")
    @DisplayName("[D-008] 다른 주문의 멱등키가 재사용돼도 해당 주문의 결제가 승인되어야 한다")
    void reusedIdempotencyKeyMustNotHijackAnotherOrder() {
        String sharedKey = "PGKEY-" + UUID.randomUUID();

        String firstOrder = preparedOrder(30_000L);
        paymentService.handleApproval(
                new PgApproval(firstOrder, pgTxIdOf(firstOrder), 30_000L, sharedKey));
        assertThat(reload(firstOrder).getStatus()).isEqualTo(PaymentStatus.PAID);

        // PG 가 같은 멱등키를 다른 주문에 재사용한 상황.
        // 멱등키는 외부가 만드는 값이라 우리 쪽에서 유일성을 강제할 수 없다.
        String secondOrder = preparedOrder(50_000L);
        long outboxBefore = outboxEventRepository.count();

        paymentService.handleApproval(
                new PgApproval(secondOrder, pgTxIdOf(secondOrder), 50_000L, sharedKey));

        // 실제로 벌어지는 일: 멱등키 조회가 첫 번째 결제를 물어오고,
        // 그 결제는 이미 PAID 라 '중복 콜백'으로 판정되어 조용히 무시된다.
        // 두 번째 주문은 PG 에서 승인됐는데 우리 장부에는 PENDING 으로 남는다 —
        // 라이선스 미지급, 주문 미확정, 정산 누락이 한꺼번에 발생한다.
        assertThat(reload(secondOrder).getStatus())
                .as("두 번째 주문의 결제 상태")
                .isEqualTo(PaymentStatus.PAID);
        assertThat(outboxEventRepository.count() - outboxBefore)
                .as("PaymentCompleted 발행 건수")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("현재 동작: 재사용된 멱등키는 두 번째 주문을 조용히 미승인 상태로 남긴다")
    void currentBehaviourLeavesSecondOrderPending() {
        String sharedKey = "PGKEY-" + UUID.randomUUID();

        String firstOrder = preparedOrder(30_000L);
        paymentService.handleApproval(
                new PgApproval(firstOrder, pgTxIdOf(firstOrder), 30_000L, sharedKey));

        String secondOrder = preparedOrder(50_000L);
        paymentService.handleApproval(
                new PgApproval(secondOrder, pgTxIdOf(secondOrder), 50_000L, sharedKey));

        // 예외도 로그 경고도 없이 PENDING 에 머문다. 관측되지 않는 것이 더 나쁘다.
        assertThat(reload(secondOrder).getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(reload(firstOrder).getStatus()).isEqualTo(PaymentStatus.PAID);
    }
}
