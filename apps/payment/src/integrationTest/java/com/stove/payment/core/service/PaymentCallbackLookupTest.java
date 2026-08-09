package com.stove.payment.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stove.common.core.error.BusinessException;
import com.stove.common.event.EventType;
import com.stove.common.event.payload.OrderLine;
import com.stove.common.messaging.outbox.OutboxEventRepository;
import com.stove.common.testcontainers.InfraContainers;
import com.stove.payment.core.domain.Payment;
import com.stove.payment.core.domain.PaymentPreparation;
import com.stove.payment.core.domain.PaymentRepository;
import com.stove.payment.core.domain.PaymentStatus;
import com.stove.payment.core.domain.PgApproval;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * 콜백이 <b>어느 결제 건에 매칭되는가</b>. 금액 대조보다 한 단계 앞선 질문이다 —
 * 매칭이 틀리면 그 다음의 모든 검증이 엉뚱한 대상 위에서 통과한다.
 *
 * <p>{@code handleApproval} 은 <b>주문번호</b>로 결제를 찾는다. 주문번호는 우리가 만든 값이라 신뢰할 수 있다.
 * 멱등키는 PG 가 만들어 주는 값이라 유일성을 우리 쪽에서 보장할 수 없으므로,
 * 역할을 "이 결제에 이미 적용된 콜백인가" 하나로 좁혔다.
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
    @DisplayName("[D-008] 다른 주문의 멱등키가 재사용돼도 해당 주문의 결제가 승인된다")
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

        // 수정 전에는 멱등키 조회가 첫 번째 결제를 물어왔고, 그 결제가 이미 PAID 라
        // '중복 콜백'으로 판정되어 두 번째 주문이 조용히 PENDING 에 머물렀다 —
        // 라이선스 미지급, 주문 미확정, 정산 누락이 한꺼번에 발생하는 상태였다.
        assertThat(reload(secondOrder).getStatus())
                .as("두 번째 주문의 결제 상태")
                .isEqualTo(PaymentStatus.PAID);
        assertThat(outboxEventRepository.count() - outboxBefore)
                .as("PaymentCompleted 발행 건수")
                .isEqualTo(1);
        assertThat(reload(firstOrder).getStatus()).isEqualTo(PaymentStatus.PAID);
    }

    @Test
    @DisplayName("[D-008] 같은 콜백이 재전송되면 승인은 한 번이고 이벤트도 한 번이다")
    void resentCallbackIsAbsorbed() {
        String orderNo = preparedOrder(30_000L);
        PgApproval approval = new PgApproval(orderNo, pgTxIdOf(orderNo), 30_000L,
                "PGKEY-" + UUID.randomUUID());
        long before = outboxEventRepository.count();

        paymentService.handleApproval(approval);
        paymentService.handleApproval(approval);

        assertThat(reload(orderNo).getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(outboxEventRepository.count() - before).isEqualTo(1);
    }

    @Test
    @DisplayName("[D-008] 승인된 주문에 다른 키의 승인이 또 오면 조용히 넘기지 않는다")
    void secondDistinctApprovalIsRejectedLoudly() {
        String orderNo = preparedOrder(30_000L);
        String pgTxId = pgTxIdOf(orderNo);
        paymentService.handleApproval(new PgApproval(orderNo, pgTxId, 30_000L, "PGKEY-" + UUID.randomUUID()));
        long before = outboxEventRepository.count();

        // PG 연동 오류이거나 위·변조다. 무시하면 사고가 관측되지 않는다.
        assertThatThrownBy(() -> paymentService.handleApproval(
                new PgApproval(orderNo, pgTxId, 30_000L, "PGKEY-" + UUID.randomUUID())))
                .isInstanceOf(BusinessException.class);

        assertThat(reload(orderNo).getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(outboxEventRepository.count() - before).isZero();
    }

    @Test
    @DisplayName("[D-008] 존재하지 않는 주문의 콜백은 거부된다")
    void unknownOrderIsRejected() {
        assertThatThrownBy(() -> paymentService.handleApproval(
                new PgApproval("ORD-" + UUID.randomUUID(), "PG-X", 30_000L, "PGKEY-" + UUID.randomUUID())))
                .isInstanceOf(BusinessException.class);
    }
}
