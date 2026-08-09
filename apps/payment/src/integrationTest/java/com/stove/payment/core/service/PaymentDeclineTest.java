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
import com.stove.payment.core.domain.PgDecline;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * PG 승인 거절 경로.
 *
 * <p>이 경로는 통째로 비어 있었다 — {@code Payment.fail()} 은 호출자가 없었고 {@code PaymentFailed}
 * 이벤트 타입도 없어서, 거절된 주문은 아무 상태도 남기지 못하고 {@code CREATED} 에 영구히 머물렀다.
 *
 * <p>승인 경로와 대칭으로 검증한다. 콜백은 외부(PG)가 보내는 요청이라 <b>재전송이 기본</b>이고,
 * 승인이 그렇듯 거절도 중복 수신이 이벤트를 두 번 만들면 안 된다. 다른 점은 멱등의 근거다 —
 * 승인은 멱등키로, 거절은 {@code FAILED} 가 종단 상태라는 점으로 흡수한다.
 */
@SpringBootTest(properties = "stove.outbox.relay-enabled=false")
@Import({InfraContainers.MySql.class, InfraContainers.Kafka.class})
class PaymentDeclineTest {

    @Autowired
    PaymentService paymentService;
    @Autowired
    PaymentRepository paymentRepository;
    @Autowired
    OutboxEventRepository outboxEventRepository;

    /** READY → PENDING 까지 밟아 거절을 받을 수 있는 결제를 만든다. */
    private PaymentPreparation preparedOrder(long amount) {
        String orderNo = "ORD-" + UUID.randomUUID();
        paymentService.createReady(UUID.randomUUID().toString(), EventType.ORDER_CREATED,
                orderNo, 42L, amount, "KRW",
                List.of(new OrderLine(1L, "게임 A", 1001L, amount, 1)));
        return paymentService.prepare(orderNo, "CARD");
    }

    private Payment reload(String orderNo) {
        return paymentRepository.findByOrderNo(orderNo).orElseThrow();
    }

    private PgDecline decline(PaymentPreparation prepared) {
        return new PgDecline(prepared.orderNo(), prepared.pgTxId(),
                "REJECT_CARD_COMPANY", "카드사 거절");
    }

    @Test
    @DisplayName("거절 콜백은 결제를 FAILED 로 끝내고 사유를 남긴다")
    void declineMarksFailed() {
        PaymentPreparation prepared = preparedOrder(30_000L);

        paymentService.handleDecline(decline(prepared));

        Payment payment = reload(prepared.orderNo());
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.getFailReasonCode()).isEqualTo("REJECT_CARD_COMPANY");
        assertThat(payment.getFailedAt()).isNotNull();
    }

    @Test
    @DisplayName("거절과 함께 PaymentFailed 가 적재된다 — order 가 실패를 알 수 있는 유일한 통로")
    void declineRecordsEvent() {
        PaymentPreparation prepared = preparedOrder(30_000L);
        long before = outboxEventRepository.count();

        paymentService.handleDecline(decline(prepared));

        assertThat(outboxEventRepository.count() - before).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 거절이 다시 와도 이벤트를 재발행하지 않는다")
    void duplicateDeclineDoesNotRepublish() {
        PaymentPreparation prepared = preparedOrder(30_000L);
        paymentService.handleDecline(decline(prepared));
        long after = outboxEventRepository.count();

        paymentService.handleDecline(decline(prepared));

        assertThat(outboxEventRepository.count()).isEqualTo(after);
        assertThat(reload(prepared.orderNo()).getStatus()).isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    @DisplayName("승인이 먼저 확정된 뒤 오는 거절은 예외다 — 엇갈린 콜백을 조용히 삼키지 않는다")
    void declineAfterApprovalRaises() {
        PaymentPreparation prepared = preparedOrder(30_000L);
        paymentService.handleApproval(new PgApproval(prepared.orderNo(), prepared.pgTxId(),
                30_000L, "PGKEY-" + UUID.randomUUID()));

        assertThatThrownBy(() -> paymentService.handleDecline(decline(prepared)))
                .isInstanceOf(BusinessException.class);

        assertThat(reload(prepared.orderNo()).getStatus()).isEqualTo(PaymentStatus.PAID);
    }

    @Test
    @DisplayName("거절이 먼저 확정된 뒤 오는 승인도 예외다 — 반대 순서에서도 대칭이다")
    void approvalAfterDeclineRaises() {
        PaymentPreparation prepared = preparedOrder(30_000L);
        paymentService.handleDecline(decline(prepared));

        assertThatThrownBy(() -> paymentService.handleApproval(
                new PgApproval(prepared.orderNo(), prepared.pgTxId(), 30_000L,
                        "PGKEY-" + UUID.randomUUID())))
                .isInstanceOf(BusinessException.class);

        assertThat(reload(prepared.orderNo()).getStatus()).isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    @DisplayName("없는 주문의 거절은 404 로 끊는다")
    void declineOnUnknownOrderRaises() {
        assertThatThrownBy(() -> paymentService.handleDecline(
                new PgDecline("ORD-NOPE", "PG-1", "REJECT_CARD_COMPANY", "카드사 거절")))
                .isInstanceOf(BusinessException.class);
    }
}
