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
 * 결제 승인의 멱등성 — 검증 게이트 4단계에 해당한다.
 *
 * <p>중복 콜백에서 확인할 것이 둘이다. 상태가 한 번만 바뀌는 것과,
 * <b>이벤트가 다시 발행되지 않는 것</b>. 후자를 놓치면 라이선스가 두 번 지급되고
 * 정산 원장에 매출이 두 번 잡힌다.
 */
@SpringBootTest(properties = "stove.outbox.relay-enabled=false")
@Import({InfraContainers.MySql.class, InfraContainers.Kafka.class})
class PaymentIdempotencyTest {

    @Autowired
    PaymentService paymentService;
    @Autowired
    PaymentRepository paymentRepository;
    @Autowired
    OutboxEventRepository outboxEventRepository;

    private String readyPayment(long amount) {
        String orderNo = "ORD-" + UUID.randomUUID();
        paymentService.createReady(UUID.randomUUID().toString(), EventType.ORDER_CREATED,
                orderNo, 42L, amount, "KRW",
                List.of(new OrderLine(1L, "게임 A", 1001L, amount, 1)));
        return orderNo;
    }

    @Test
    @DisplayName("중복 콜백은 승인을 한 번만 확정하고 이벤트를 재발행하지 않는다")
    void duplicateCallbackApprovesOnce() {
        String orderNo = readyPayment(30_000L);
        PaymentPreparation prepared = paymentService.prepare(orderNo, "CARD");
        String idempotencyKey = "PGKEY-" + UUID.randomUUID();
        long outboxBefore = outboxEventRepository.count();

        PgApproval approval = new PgApproval(orderNo, prepared.pgTxId(), 30_000L, idempotencyKey);
        paymentService.handleApproval(approval);
        paymentService.handleApproval(approval);

        Payment payment = paymentRepository.findByOrderNo(orderNo).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(outboxEventRepository.count() - outboxBefore).isEqualTo(1);
    }

    @Test
    @DisplayName("결제 대기 생성도 중복 수신에 안전하다")
    void createReadyIsIdempotent() {
        String eventId = UUID.randomUUID().toString();
        String orderNo = "ORD-" + UUID.randomUUID();
        List<OrderLine> lines = List.of(new OrderLine(1L, "게임 A", 1001L, 10_000L, 1));

        paymentService.createReady(eventId, EventType.ORDER_CREATED, orderNo, 42L, 10_000L, "KRW", lines);
        paymentService.createReady(eventId, EventType.ORDER_CREATED, orderNo, 42L, 10_000L, "KRW", lines);

        assertThat(paymentRepository.findByOrderNo(orderNo)).isPresent();
        assertThat(paymentRepository.findByOrderNo(orderNo).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.READY);
    }

    @Test
    @DisplayName("승인 금액이 다르면 확정하지 않는다 — 멱등성과 무관한 별개의 방어선")
    void rejectsAmountMismatch() {
        String orderNo = readyPayment(30_000L);
        PaymentPreparation prepared = paymentService.prepare(orderNo, "CARD");

        assertThatThrownBy(() -> paymentService.handleApproval(
                new PgApproval(orderNo, prepared.pgTxId(), 1_000L, "PGKEY-" + UUID.randomUUID())))
                .isInstanceOf(BusinessException.class);

        assertThat(paymentRepository.findByOrderNo(orderNo).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.PENDING);
    }
}
