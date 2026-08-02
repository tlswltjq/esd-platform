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
import static org.mockito.Mockito.verify;

import com.stove.common.core.error.BusinessException;
import com.stove.common.event.EventType;
import com.stove.common.event.payload.OrderLine;
import com.stove.common.messaging.outbox.OutboxRecorder;
import com.stove.common.test.InfraContainers;
import com.stove.payment.core.domain.Payment;
import com.stove.payment.core.domain.PaymentPreparation;
import com.stove.payment.core.domain.PaymentRepository;
import com.stove.payment.core.domain.PaymentStatus;
import com.stove.payment.core.domain.PgApproval;
import com.stove.payment.core.port.PgClient;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;

/**
 * 환불 경로의 실패 시나리오.
 *
 * <p>환불은 <b>되돌릴 수 없는 외부 호출</b>(PG 취소)과 <b>되돌릴 수 있는 로컬 변경</b>(DB·Outbox)이
 * 한 트랜잭션에 섞이는 지점이다. 정상 경로만 보면 문제가 없어 보이므로,
 * 여기서는 로컬 변경이 실패했을 때 외부 세계가 어떤 상태로 남는지를 관찰한다.
 */
@SpringBootTest(properties = "stove.outbox.relay-enabled=false")
@Import({InfraContainers.MySql.class, InfraContainers.Kafka.class})
class PaymentCancelTest {

    @Autowired
    PaymentService paymentService;
    @Autowired
    PaymentRepository paymentRepository;

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

    private PaymentStatus statusOf(String orderNo) {
        return paymentRepository.findByOrderNo(orderNo).orElseThrow().getStatus();
    }

    @Test
    @DisplayName("환불하면 상태가 CANCELED 로 바뀌고 PG 취소가 호출된다")
    void cancelRefundsAndMarksCanceled() {
        String orderNo = paidOrder(30_000L);

        paymentService.cancel(orderNo, "USER_REFUND");

        assertThat(statusOf(orderNo)).isEqualTo(PaymentStatus.CANCELED);
        verify(pgClient).cancel(anyString(), anyLong(), anyString());
    }

    @Test
    @DisplayName("이미 취소된 결제는 PG 를 다시 호출하지 않는다 — 이중 환불 방지")
    void secondCancelDoesNotCallPgAgain() {
        String orderNo = paidOrder(30_000L);
        paymentService.cancel(orderNo, "USER_REFUND");
        reset(pgClient);

        paymentService.cancel(orderNo, "USER_REFUND");

        verify(pgClient, never()).cancel(anyString(), anyLong(), anyString());
        assertThat(statusOf(orderNo)).isEqualTo(PaymentStatus.CANCELED);
    }

    @Test
    @DisplayName("승인 전 결제는 취소할 수 없다")
    void cannotCancelBeforeApproval() {
        String orderNo = "ORD-" + UUID.randomUUID();
        paymentService.createReady(UUID.randomUUID().toString(), EventType.ORDER_CREATED,
                orderNo, 42L, 30_000L, "KRW",
                List.of(new OrderLine(1L, "게임 A", 1001L, 30_000L, 1)));

        assertThatThrownBy(() -> paymentService.cancel(orderNo, "USER_REFUND"))
                .isInstanceOf(BusinessException.class);
        assertThat(statusOf(orderNo)).isEqualTo(PaymentStatus.READY);
    }

    @Test
    @DisplayName("보상 환불도 사용자 환불과 같은 결과를 낸다")
    void compensationRefundsLikeUserRequest() {
        String orderNo = paidOrder(30_000L);

        paymentService.compensate(UUID.randomUUID().toString(), EventType.LICENSE_ISSUE_FAILED,
                orderNo, "라이선스 지급 실패");

        assertThat(statusOf(orderNo)).isEqualTo(PaymentStatus.CANCELED);
    }

    @Test
    @Tag("known-defect")
    @DisplayName("[D-006] 로컬 트랜잭션이 롤백되면 PG 환불도 실행되지 않아야 한다")
    void shouldNotRefundWhenTransactionRollsBack() {
        String orderNo = paidOrder(30_000L);

        // 환불 이벤트 적재가 실패하는 상황. 커넥션 끊김·제약 위반·직렬화 실패 어느 쪽이든
        // 결과는 같다 — 트랜잭션 전체가 롤백된다.
        doThrow(new IllegalStateException("outbox insert failed"))
                .when(recorderSpy()).record(anyString(), anyString(), any());

        assertThatThrownBy(() -> paymentService.cancel(orderNo, "USER_REFUND"))
                .isInstanceOf(IllegalStateException.class);

        // 로컬 상태는 정상적으로 되돌아간다
        assertThat(statusOf(orderNo)).isEqualTo(PaymentStatus.PAID);

        // 기대: 되돌릴 수 없는 외부 호출은 커밋이 보장된 뒤에 실행한다.
        // 실제: PG 취소가 트랜잭션 안에서 이미 나갔다. 돈은 나갔는데 장부는 PAID —
        //       재시도하면 같은 결제를 두 번 환불한다.
        verify(pgClient, never()).cancel(anyString(), anyLong(), anyString());
    }

    @Test
    @Tag("known-defect")
    @DisplayName("[D-007] 결제 상태와 어긋난 보상 요청은 무한 재시도가 아니라 관측 가능하게 끝나야 한다")
    void compensationOnInconsistentStateShouldNotLoop() {
        // 결제가 아직 PENDING 인데 라이선스 지급 실패 이벤트가 도착한 상황.
        // 정상 흐름이라면 생길 수 없지만, 이벤트 순서 역전이나 수동 재처리로 실제로 발생한다.
        String orderNo = "ORD-" + UUID.randomUUID();
        paymentService.createReady(UUID.randomUUID().toString(), EventType.ORDER_CREATED,
                orderNo, 42L, 30_000L, "KRW",
                List.of(new OrderLine(1L, "게임 A", 1001L, 30_000L, 1)));
        paymentService.prepare(orderNo, "CARD");

        // 기대: 상태 불일치를 기록하고 끝난다(멱등 가드 마킹이 커밋되어 재전송이 멈춘다).
        // 실제: BusinessException(CONFLICT) 이 나면서 가드 마킹까지 함께 롤백된다.
        //       → 오프셋도 커밋되지 않아 같은 이벤트가 계속 재전송되고 파티션이 멈춘다.
        assertThatCode(() -> paymentService.compensate(UUID.randomUUID().toString(),
                EventType.LICENSE_ISSUE_FAILED, orderNo, "라이선스 지급 실패"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("현재 동작: PENDING 결제에 대한 보상 요청은 예외로 끝난다")
    void currentBehaviourCompensationThrowsOnPending() {
        String orderNo = "ORD-" + UUID.randomUUID();
        paymentService.createReady(UUID.randomUUID().toString(), EventType.ORDER_CREATED,
                orderNo, 42L, 30_000L, "KRW",
                List.of(new OrderLine(1L, "게임 A", 1001L, 30_000L, 1)));
        paymentService.prepare(orderNo, "CARD");

        assertThatThrownBy(() -> paymentService.compensate(UUID.randomUUID().toString(),
                EventType.LICENSE_ISSUE_FAILED, orderNo, "라이선스 지급 실패"))
                .isInstanceOf(BusinessException.class);

        Payment payment = paymentRepository.findByOrderNo(orderNo).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
    }
}
