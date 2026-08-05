package com.stove.payment.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

import com.stove.common.core.error.BusinessException;
import com.stove.common.core.error.ErrorCode;
import com.stove.common.event.EventType;
import com.stove.common.event.payload.OrderLine;
import com.stove.common.messaging.outbox.OutboxEventRepository;
import com.stove.common.test.InfraContainers;
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
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * 검증 게이트 2·3 — <b>금액이 어디서 확정되고 무엇과 대조되는가.</b>
 *
 * <p>기존 테스트가 이 두 게이트를 덮지 못한 이유는 단언의 모양 때문이었다.
 * 게이트 2는 {@code pgClient.prepare} 에 무엇이 넘어가는지 아무도 보지 않았고,
 * 게이트 3은 {@code isInstanceOf(BusinessException.class)} 로만 받아
 * {@code PAYMENT_ALREADY_PROCESSED} 가 나와도 통과했다.
 *
 * <p>여기서는 <b>값</b>으로 단언한다 — PG 에 실린 금액과 거부의 에러 코드.
 */
@SpringBootTest(properties = "stove.outbox.relay-enabled=false")
@Import({InfraContainers.MySql.class, InfraContainers.Kafka.class})
class PaymentGateTest {

    @Autowired
    PaymentService paymentService;
    @Autowired
    PaymentRepository paymentRepository;
    @Autowired
    OutboxEventRepository outboxEventRepository;

    @MockitoSpyBean
    PgClient pgClient;

    @AfterEach
    void tearDown() {
        reset(pgClient);
    }

    private String readyPayment(long amount) {
        String orderNo = "ORD-" + UUID.randomUUID();
        paymentService.createReady(UUID.randomUUID().toString(), EventType.ORDER_CREATED,
                orderNo, 42L, amount, "KRW",
                List.of(new OrderLine(1L, "게임 A", 1001L, amount, 1)));
        return orderNo;
    }

    private PaymentStatus statusOf(String orderNo) {
        return paymentRepository.findByOrderNo(orderNo).orElseThrow().getStatus();
    }

    // ── 게이트 2: PG 사전등록 ──────────────────────────────────────────

    @Test
    @DisplayName("[게이트 2] 사전등록에는 서버가 보관한 금액이 실린다")
    void prepareRegistersTheServerHeldAmountWithPg() {
        String orderNo = readyPayment(30_000L);

        paymentService.prepare(orderNo, "CARD");

        // 게이트 2의 주장은 "승인 전에 서버가 결제 금액을 PG 에 먼저 등록한다"는 것이다.
        // 그 주장은 PG 에 실제로 넘어간 값을 봐야만 검증된다.
        ArgumentCaptor<Long> amount = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<String> currency = ArgumentCaptor.forClass(String.class);
        verify(pgClient).prepare(eq(orderNo), amount.capture(), currency.capture(), eq("CARD"));

        assertThat(amount.getValue())
                .as("PG 에 등록된 금액이 서버 보관 금액과 다르다 — 게이트 2가 무의미해진다")
                .isEqualTo(30_000L);
        assertThat(currency.getValue()).isEqualTo("KRW");
    }

    @Test
    @DisplayName("[게이트 2] 클라이언트에게 돌려주는 금액도 서버 확정 금액이다")
    void preparationEchoesTheServerHeldAmount() {
        String orderNo = readyPayment(57_000L);

        PaymentPreparation prepared = paymentService.prepare(orderNo, "STOVE_CASH");

        assertThat(prepared.amount()).isEqualTo(57_000L);
        assertThat(prepared.currency()).isEqualTo("KRW");
    }

    // ── 게이트 3: 콜백 금액 대조 ────────────────────────────────────────

    @Test
    @DisplayName("[게이트 3] 과소결제는 PAYMENT_AMOUNT_MISMATCH 로 거부된다")
    void underpaymentIsRejectedWithMismatchCode() {
        String orderNo = readyPayment(30_000L);
        PaymentPreparation prepared = paymentService.prepare(orderNo, "CARD");

        assertThatThrownBy(() -> paymentService.handleApproval(
                new PgApproval(orderNo, prepared.pgTxId(), 1_000L, "PGKEY-" + UUID.randomUUID())))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .as("어떤 BusinessException 이든 통과하면 게이트 3이 사라져도 초록이다")
                .isEqualTo(ErrorCode.PAYMENT_AMOUNT_MISMATCH);
    }

    @Test
    @DisplayName("[게이트 3] 과다결제도 거부된다 — 더 낸 돈은 정산이 설명할 수 없다")
    void overpaymentIsRejectedWithMismatchCode() {
        String orderNo = readyPayment(30_000L);
        PaymentPreparation prepared = paymentService.prepare(orderNo, "CARD");

        // 방향만 다를 뿐 같은 위반이다. 승인해 버리면 결제 원장과 정산 원장이 어긋나고,
        // 그 차액은 어느 쪽 장부에도 근거가 없다.
        assertThatThrownBy(() -> paymentService.handleApproval(
                new PgApproval(orderNo, prepared.pgTxId(), 300_000L, "PGKEY-" + UUID.randomUUID())))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.PAYMENT_AMOUNT_MISMATCH);
    }

    @Test
    @DisplayName("[게이트 3] 금액이 어긋난 승인은 상태도 이벤트도 남기지 않는다")
    void mismatchLeavesNeitherStateNorEvent() {
        String orderNo = readyPayment(30_000L);
        PaymentPreparation prepared = paymentService.prepare(orderNo, "CARD");
        long before = outboxEventRepository.count();

        assertThatThrownBy(() -> paymentService.handleApproval(
                new PgApproval(orderNo, prepared.pgTxId(), 29_999L, "PGKEY-" + UUID.randomUUID())))
                .isInstanceOf(BusinessException.class);

        assertThat(statusOf(orderNo)).isEqualTo(PaymentStatus.PENDING);
        assertThat(outboxEventRepository.count() - before)
                .as("거부된 승인이 PaymentCompleted 를 흘리면 라이선스가 지급된다")
                .isZero();
    }

    // ── createReady 2차 가드 ────────────────────────────────────────────

    @Test
    @DisplayName("다른 eventId 로 온 같은 주문의 결제 생성은 2차 가드가 막는다")
    void createReadyIsGuardedByOrderNoNotOnlyByEventId() {
        String orderNo = "ORD-" + UUID.randomUUID();
        List<OrderLine> lines = List.of(new OrderLine(1L, "게임 A", 1001L, 30_000L, 1));

        paymentService.createReady(UUID.randomUUID().toString(), EventType.ORDER_CREATED,
                orderNo, 42L, 30_000L, "KRW", lines);

        // eventId 가 다르므로 Inbox 가드(1차)는 통과한다. 같은 주문이 두 번 발행됐거나
        // 릴레이가 재발행한 경우다. 여기서 걸리는 것은 existsByOrderNo(2차) 뿐이다 —
        // 2차 가드를 지우면 orderNo 유니크 제약에 걸려 이 호출이 터진다.
        assertThatCode(() -> paymentService.createReady(UUID.randomUUID().toString(),
                EventType.ORDER_CREATED, orderNo, 42L, 30_000L, "KRW", lines))
                .as("2차 가드가 없으면 유니크 위반이 컨슈머 밖으로 나간다")
                .doesNotThrowAnyException();

        assertThat(paymentRepository.findByOrderNo(orderNo)).isPresent();
    }
}
