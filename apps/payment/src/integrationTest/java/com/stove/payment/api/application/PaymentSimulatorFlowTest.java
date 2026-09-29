package com.stove.payment.api.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stove.common.core.error.BusinessException;
import com.stove.common.core.error.ErrorCode;
import com.stove.common.event.EventType;
import com.stove.common.event.payload.OrderLine;
import com.stove.common.messaging.outbox.OutboxEventRepository;
import com.stove.common.testcontainers.InfraContainers;
import com.stove.payment.core.domain.PaymentPreparation;
import com.stove.payment.core.domain.PaymentStatus;
import com.stove.payment.core.service.PaymentService;
import com.stove.payment.core.service.SimulatedPgService;
import com.stove.payment.core.domain.SimulatedPgTransaction;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = "stove.outbox.relay-enabled=false")
@ActiveProfiles("test")
@Import({InfraContainers.MySql.class, InfraContainers.Kafka.class})
class PaymentSimulatorFlowTest {

    @Autowired PaymentService paymentService;
    @Autowired PaymentSimulatorFacade simulator;
    @Autowired SimulatedPgService pgClient;
    @Autowired OutboxEventRepository events;

    private PaymentPreparation prepared() {
        String orderNo = "ORD-" + UUID.randomUUID();
        paymentService.createReady(UUID.randomUUID().toString(), EventType.ORDER_CREATED,
                orderNo, 42L, 18_000, "KRW",
                List.of(new OrderLine(1L, "게임 A", 1001L, 18_000, 1)));
        return paymentService.prepare(orderNo, "CARD");
    }

    @Test
    @DisplayName("승인과 환불은 결제 상태를 전이하고 중복 요청에도 이벤트를 한 번씩만 적재한다")
    void approvalAndRefundUsePaymentTransitionsAndEmitOneEventEach() {
        PaymentPreparation preparation = prepared();
        assertThat(preparation.redirectUrl()).contains("/api/v1/payments/simulator/checkout/");
        assertThat(simulator.checkout(preparation.pgTxId(), 42L).getAmount()).isEqualTo(18_000);
        assertThatThrownBy(() -> simulator.checkout(preparation.pgTxId(), 43L))
                .isInstanceOf(BusinessException.class);

        long before = events.count();
        assertThat(simulator.approve(preparation.orderNo()).getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(events.count() - before).isEqualTo(1);
        simulator.approve(preparation.orderNo());
        assertThat(events.count() - before).isEqualTo(1);

        assertThat(simulator.refund(preparation.orderNo()).getStatus()).isEqualTo(PaymentStatus.CANCELED);
        assertThat(pgClient.get(preparation.pgTxId()).getStatus())
                .isEqualTo(SimulatedPgTransaction.Status.CANCELED);
        assertThat(events.count() - before).isEqualTo(2);
        simulator.refund(preparation.orderNo());
        assertThat(events.count() - before).isEqualTo(2);
    }

    @Test
    @DisplayName("거절과 시간 초과는 각각 실패 사유를 남기고 이후 승인을 막는다")
    void declineAndTimeoutEndSeparateOrdersAndRejectLateApproval() {
        PaymentPreparation declined = prepared();
        assertThat(simulator.decline(declined.orderNo(), "CARD_DECLINED", "카드 거절").getStatus())
                .isEqualTo(PaymentStatus.FAILED);
        assertThat(paymentService.getPayment(declined.orderNo()).getFailReasonCode())
                .isEqualTo("CARD_DECLINED");
        simulator.decline(declined.orderNo(), "CARD_DECLINED", "카드 거절");
        assertThatThrownBy(() -> simulator.approve(declined.orderNo()))
                .isInstanceOf(BusinessException.class);

        PaymentPreparation timedOut = prepared();
        assertThat(simulator.timeout(timedOut.orderNo()).getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(paymentService.getPayment(timedOut.orderNo()).getFailReasonCode())
                .isEqualTo("PG_TIMEOUT");
        assertThat(pgClient.get(timedOut.pgTxId()).getStatus())
                .isEqualTo(SimulatedPgTransaction.Status.TIMED_OUT);
        assertThatThrownBy(() -> simulator.approve(timedOut.orderNo()))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.CONFLICT));
    }

    @Test
    @DisplayName("재사전등록한 결제의 예전 체크아웃은 거부하고 현재 거래만 승인한다")
    void oldCheckoutCannotApproveAfterPaymentIsPreparedAgain() {
        PaymentPreparation first = prepared();
        PaymentPreparation second = paymentService.prepare(first.orderNo(), "CARD");
        assertThat(first.pgTxId()).isNotEqualTo(second.pgTxId());

        assertThatThrownBy(() -> simulator.checkout(first.pgTxId(), 42L))
                .isInstanceOf(BusinessException.class);
        assertThat(simulator.checkout(second.pgTxId(), 42L).getStatus())
                .isEqualTo(SimulatedPgTransaction.Status.PREPARED);
        assertThat(simulator.approve(second.orderNo()).getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(pgClient.get(first.pgTxId()).getStatus())
                .isEqualTo(SimulatedPgTransaction.Status.PREPARED);
    }

    @Test
    @DisplayName("사전등록하지 않은 결제는 시뮬레이터 승인·거절·시간 초과·환불을 모두 거부한다")
    void controlsRequirePreparedPayment() {
        String orderNo = "ORD-" + UUID.randomUUID();
        paymentService.createReady(UUID.randomUUID().toString(), EventType.ORDER_CREATED,
                orderNo, 42L, 18_000, "KRW",
                List.of(new OrderLine(1L, "게임 A", 1001L, 18_000, 1)));

        assertThatThrownBy(() -> simulator.approve(orderNo))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.CONFLICT));
        assertThatThrownBy(() -> simulator.decline(orderNo, "CARD_DECLINED", "카드 거절"))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.CONFLICT));
        assertThatThrownBy(() -> simulator.timeout(orderNo))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.CONFLICT));
        assertThatThrownBy(() -> simulator.refund(orderNo))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.CONFLICT));
        assertThat(paymentService.getPayment(orderNo).getStatus()).isEqualTo(PaymentStatus.READY);
    }
}
