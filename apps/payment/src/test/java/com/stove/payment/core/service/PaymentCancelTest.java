package com.stove.payment.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.stove.common.core.error.BusinessException;
import com.stove.common.event.EventType;
import com.stove.common.event.payload.OrderLine;
import com.stove.common.messaging.inbox.ProcessedEventRepository;
import com.stove.common.messaging.outbox.OutboxEventRepository;
import com.stove.common.messaging.outbox.OutboxRecorder;
import com.stove.common.test.InfraContainers;
import com.stove.payment.api.application.RefundFacade;
import com.stove.payment.core.domain.PaymentPreparation;
import com.stove.payment.core.domain.PaymentRepository;
import com.stove.payment.core.domain.PaymentStatus;
import com.stove.payment.core.domain.PgApproval;
import com.stove.payment.core.port.PgClient;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;

/**
 * 환불 경로. <b>되돌릴 수 없는 외부 호출</b>(PG 취소)과 <b>되돌릴 수 있는 로컬 변경</b>(DB·Outbox)이
 * 만나는 지점이라, 정상 경로보다 중간에 끊겼을 때의 상태가 중요하다.
 *
 * <p>취소는 세 걸음으로 나뉜다 — 의도 기록 커밋 → PG 환불 → 확정 커밋.
 * 어느 걸음에서 멈추든 남는 상태가 관측 가능해야 한다는 것이 여기서 검증할 성질이다.
 */
@SpringBootTest(properties = "stove.outbox.relay-enabled=false")
@Import({InfraContainers.MySql.class, InfraContainers.Kafka.class})
class PaymentCancelTest {

    @Autowired
    PaymentService paymentService;
    @Autowired
    RefundFacade refundFacade;
    @Autowired
    PaymentRepository paymentRepository;
    @Autowired
    OutboxEventRepository outboxEventRepository;
    @Autowired
    ProcessedEventRepository processedEventRepository;

    @MockitoSpyBean
    PgClient pgClient;

    /**
     * {@code OutboxRecorder} 는 {@code @Transactional(MANDATORY)} 때문에 CGLIB 프록시로 감싸여 있다.
     * 주입된 참조를 그대로 스텁하면 {@code when(...)} 단계의 호출이 프록시를 타면서
     * 트랜잭션 밖에서 실제 메서드가 실행돼 버린다. 대역 자체를 꺼내 스텁해야 한다.
     */
    @MockitoSpyBean
    OutboxRecorder outboxRecorder;

    private OutboxRecorder recorderSpy() {
        return AopTestUtils.getTargetObject(outboxRecorder);
    }

    @AfterEach
    void tearDown() {
        reset(pgClient, recorderSpy());
    }

    /** READY → PENDING → PAID 까지 밟아 환불 가능한 결제를 만든다. */
    private String paidOrder(long amount) {
        String orderNo = "ORD-" + UUID.randomUUID();
        paymentService.createReady(UUID.randomUUID().toString(), EventType.ORDER_CREATED,
                orderNo, 42L, amount, "KRW",
                List.of(new OrderLine(1L, "게임 A", 1001L, amount, 1)));
        PaymentPreparation prepared = paymentService.prepare(orderNo, "CARD");
        paymentService.handleApproval(new PgApproval(orderNo, prepared.pgTxId(), amount,
                "PGKEY-" + UUID.randomUUID()));
        return orderNo;
    }

    private String preparedOrder(long amount) {
        String orderNo = "ORD-" + UUID.randomUUID();
        paymentService.createReady(UUID.randomUUID().toString(), EventType.ORDER_CREATED,
                orderNo, 42L, amount, "KRW",
                List.of(new OrderLine(1L, "게임 A", 1001L, amount, 1)));
        paymentService.prepare(orderNo, "CARD");
        return orderNo;
    }

    private PaymentStatus statusOf(String orderNo) {
        return paymentRepository.findByOrderNo(orderNo).orElseThrow().getStatus();
    }

    @Test
    @DisplayName("환불하면 상태가 CANCELED 로 바뀌고 PG 취소가 호출된다")
    void cancelRefundsAndMarksCanceled() {
        String orderNo = paidOrder(30_000L);

        refundFacade.refund(orderNo, "USER_REFUND");

        assertThat(statusOf(orderNo)).isEqualTo(PaymentStatus.CANCELED);
        verify(pgClient).cancel(anyString(), anyLong(), anyString());
    }

    @Test
    @DisplayName("환불 확정과 함께 PaymentCancelled 가 적재된다")
    void cancelRecordsEvent() {
        String orderNo = paidOrder(30_000L);
        long before = outboxEventRepository.count();

        refundFacade.refund(orderNo, "USER_REFUND");

        assertThat(outboxEventRepository.count() - before).isEqualTo(1);
    }

    @Test
    @DisplayName("이미 취소된 결제는 PG 를 다시 호출하지 않는다 — 이중 환불 방지")
    void secondCancelDoesNotCallPgAgain() {
        String orderNo = paidOrder(30_000L);
        refundFacade.refund(orderNo, "USER_REFUND");
        reset(pgClient);

        refundFacade.refund(orderNo, "USER_REFUND");

        verify(pgClient, never()).cancel(anyString(), anyLong(), anyString());
        assertThat(statusOf(orderNo)).isEqualTo(PaymentStatus.CANCELED);
    }

    @Test
    @DisplayName("승인 전 결제는 취소할 수 없다")
    void cannotCancelBeforeApproval() {
        String orderNo = preparedOrder(30_000L);

        assertThatThrownBy(() -> refundFacade.refund(orderNo, "USER_REFUND"))
                .isInstanceOf(BusinessException.class);
        assertThat(statusOf(orderNo)).isEqualTo(PaymentStatus.PENDING);
        verify(pgClient, never()).cancel(anyString(), anyLong(), anyString());
    }

    @Test
    @DisplayName("보상 환불도 사용자 환불과 같은 결과를 낸다")
    void compensationRefundsLikeUserRequest() {
        String orderNo = paidOrder(30_000L);

        refundFacade.compensate(UUID.randomUUID().toString(), EventType.LICENSE_ISSUE_FAILED,
                orderNo, "라이선스 지급 실패");

        assertThat(statusOf(orderNo)).isEqualTo(PaymentStatus.CANCELED);
    }

    @Test
    @DisplayName("같은 보상 이벤트가 두 번 와도 PG 는 한 번만 호출된다")
    void compensationIsIdempotent() {
        String orderNo = paidOrder(30_000L);
        String eventId = UUID.randomUUID().toString();

        refundFacade.compensate(eventId, EventType.LICENSE_ISSUE_FAILED, orderNo, "라이선스 지급 실패");
        refundFacade.compensate(eventId, EventType.LICENSE_ISSUE_FAILED, orderNo, "라이선스 지급 실패");

        verify(pgClient, times(1)).cancel(anyString(), anyLong(), anyString());
    }

    @Test
    @DisplayName("[D-006] 확정 트랜잭션이 깨져도 결제가 PAID 로 되돌아가지 않는다")
    void brokenFinalizationLeavesObservableState() {
        String orderNo = paidOrder(30_000L);

        // 확정 단계의 이벤트 적재가 실패하는 상황.
        doThrow(new IllegalStateException("outbox insert failed"))
                .when(recorderSpy()).record(anyString(), anyString(), any());

        assertThatThrownBy(() -> refundFacade.refund(orderNo, "USER_REFUND"))
                .isInstanceOf(IllegalStateException.class);

        // 수정 전에는 PG 환불이 트랜잭션 안에서 나간 뒤 롤백되어 PAID 로 되돌아갔다 —
        // 돈은 나갔는데 장부는 아무 일도 없던 것처럼 보이는 상태였다.
        // 이제 의도가 먼저 커밋되므로 '환불 진행 중'이 남아 재시도 대상이 된다.
        assertThat(statusOf(orderNo)).isEqualTo(PaymentStatus.CANCELING);
    }

    @Test
    @DisplayName("[D-006] 확정이 깨진 건은 재시도로 취소가 완결된다")
    void interruptedCancelCompletesOnRetry() {
        String orderNo = paidOrder(30_000L);
        doThrow(new IllegalStateException("outbox insert failed"))
                .when(recorderSpy()).record(anyString(), anyString(), any());
        assertThatThrownBy(() -> refundFacade.refund(orderNo, "USER_REFUND"))
                .isInstanceOf(IllegalStateException.class);

        reset(recorderSpy());
        refundFacade.refund(orderNo, "USER_REFUND");

        assertThat(statusOf(orderNo)).isEqualTo(PaymentStatus.CANCELED);
        // PG 취소는 pgTxId 기준 멱등이라는 포트 계약에 기대어 재요청한다.
        verify(pgClient, times(2)).cancel(anyString(), anyLong(), anyString());
    }

    @Test
    @DisplayName("[D-007] 결제 상태와 어긋난 보상 요청은 예외 없이 끝난다")
    void compensationOnInconsistentStateDoesNotThrow() {
        // 결제가 아직 PENDING 인데 라이선스 지급 실패 이벤트가 도착한 상황.
        // 정상 흐름이라면 생길 수 없지만 이벤트 순서 역전이나 수동 재처리로 실제로 발생한다.
        String orderNo = preparedOrder(30_000L);

        // 예외를 던지면 멱등 가드 마킹까지 롤백되어 같은 이벤트가 무한 재전송되고 파티션이 멈춘다.
        // 결제가 스스로 PAID 가 될 수는 없으므로 재시도로는 절대 풀리지 않는다.
        assertThatCode(() -> refundFacade.compensate(UUID.randomUUID().toString(),
                EventType.LICENSE_ISSUE_FAILED, orderNo, "라이선스 지급 실패"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("[D-007] 상태가 어긋난 보상은 결제를 건드리지 않고 PG 도 부르지 않는다")
    void compensationOnInconsistentStateChangesNothing() {
        String orderNo = preparedOrder(30_000L);
        long before = outboxEventRepository.count();

        refundFacade.compensate(UUID.randomUUID().toString(), EventType.LICENSE_ISSUE_FAILED,
                orderNo, "라이선스 지급 실패");

        assertThat(statusOf(orderNo)).isEqualTo(PaymentStatus.PENDING);
        verify(pgClient, never()).cancel(anyString(), anyLong(), anyString());
        assertThat(outboxEventRepository.count() - before).isZero();
    }

    @Test
    @DisplayName("[D-018] 결제가 없는 주문의 보상 요청도 예외 없이 끝난다")
    void compensationForUnknownOrderMustNotStallTheConsumer() {
        // license 가 지급에 실패했는데 payment 쪽에 그 주문의 결제가 없는 상황.
        // 주문번호가 어긋났거나(연동 오류), 결제 생성 이벤트를 아직 못 받았거나, 수동 재처리다.
        String unknownOrderNo = "ORD-" + UUID.randomUUID();

        // 수정 전에는 가드를 마킹한 직후 findPayment 가 PAYMENT_NOT_FOUND 를 던졌다.
        // 예외가 리스너 밖으로 나가면서 가드 마킹까지 함께 롤백되고, 결제는 재시도한다고
        // 생기지 않으므로 같은 이벤트가 영원히 돌아왔다 — D-007 이 상태 불일치 분기에 대해
        // 막아 둔 바로 그 모양인데, not-found 경로만 빠져 있었다.
        // 이제 D-007 과 같이 소비는 진행하고 log.error 로 남긴다.
        assertThatCode(() -> refundFacade.compensate(UUID.randomUUID().toString(),
                EventType.LICENSE_ISSUE_FAILED, unknownOrderNo, "라이선스 지급 실패"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("[D-018] 결제가 없는 주문의 보상은 재수신되지 않도록 마킹이 남는다")
    void compensationForUnknownOrderKeepsTheGuardMark() {
        String unknownOrderNo = "ORD-" + UUID.randomUUID();
        String eventId = UUID.randomUUID().toString();

        assertThatCode(() -> refundFacade.compensate(eventId, EventType.LICENSE_ISSUE_FAILED,
                unknownOrderNo, "라이선스 지급 실패"))
                .doesNotThrowAnyException();

        // 마킹이 남아야 재전송이 걸러진다. 예외로 롤백되면 이 행이 없어 무한 재시도가 된다.
        assertThat(processedEventRepository.existsByEventIdAndConsumerGroup(eventId, "payment"))
                .as("멱등 가드 마킹이 롤백됐다 — 같은 이벤트가 계속 돌아온다")
                .isTrue();
    }
}
