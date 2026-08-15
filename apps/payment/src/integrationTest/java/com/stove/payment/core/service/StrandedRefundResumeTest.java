package com.stove.payment.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

import com.stove.common.event.EventType;
import com.stove.common.event.payload.OrderLine;
import com.stove.common.messaging.outbox.OutboxEvent;
import com.stove.common.messaging.outbox.OutboxEventRepository;
import com.stove.common.testcontainers.InfraContainers;
import com.stove.payment.api.application.RefundFacade;
import com.stove.payment.core.domain.PaymentRepository;
import com.stove.payment.core.domain.PaymentStatus;
import com.stove.payment.core.domain.PgApproval;
import com.stove.payment.core.port.PgClient;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * 중단된 취소({@code CANCELING})를 이어서 끝낸다.
 *
 * <p>취소는 "의도 기록 → PG 환불 → 확정" 세 걸음이고 PG 환불이 트랜잭션 밖에 있다.
 * 그 사이에서 멈추면 <b>돈이 나갔는지 불확실한 상태</b>로 남는데,
 * 설계는 "재시도 대상으로 눈에 띈다" 까지였고 <b>재시도를 거는 쪽이 없었다.</b>
 *
 * <p>관측 가능하게 만들어 둔 것과 실제로 해소되는 것은 다르다 —
 * 사용자에게는 그동안 "환불했다는데 돈이 안 들어왔다" 로 보인다.
 *
 * <p>재개가 안전한 근거는 {@link PgClient#cancel} 의 멱등 계약 하나다.
 * 실제로 나갔는지 모르는 채로 다시 걸어도 이중 환불이 되지 않는다 —
 * <b>그 계약이 없으면 이 기능은 존재할 수 없다.</b>
 */
@SpringBootTest(properties = {
        "stove.outbox.relay-enabled=false",
        "stove.payment.refund-resume-after=0s"
})
@Import({InfraContainers.MySql.class, InfraContainers.Kafka.class})
class StrandedRefundResumeTest {

    private static final long AMOUNT = 39_000L;
    /** 0초를 그대로 쓰면 "방금 착수한 건" 까지 집는지가 검증되지 않는다. 그 경계는 별도 테스트가 본다. */
    private static final Duration IMMEDIATELY = Duration.ZERO;

    @Autowired
    PaymentService paymentService;
    @Autowired
    RefundFacade refundFacade;
    @Autowired
    PaymentRepository paymentRepository;
    @Autowired
    OutboxEventRepository outboxEventRepository;
    @Autowired
    JdbcTemplate jdbcTemplate;

    @MockitoSpyBean
    PgClient pgClient;

    /**
     * 결제 행을 비우고 시작한다.
     *
     * <p>다른 통합 테스트는 주문번호가 유일해서 청소가 필요 없지만, 여기는 <b>전역 카운트</b>로
     * 판정한다 — {@code resumeStranded} 는 "지금 남아 있는 CANCELING 전부" 를 집기 때문이다.
     * 앞선 회차가 남긴 행이 그대로 있으면 "이번에 몇 건 재개됐나" 가 섞인다.
     * (그걸 모르고 돌렸다가 expected 1 / actual 2 로 두 건이 빨개졌다.)
     */
    @BeforeEach
    void clean() {
        jdbcTemplate.update("delete from payment");
    }

    @AfterEach
    void tearDown() {
        reset(pgClient);
    }

    private String paidPayment() {
        String orderNo = "ORD-" + UUID.randomUUID();
        paymentService.createReady(UUID.randomUUID().toString(), EventType.ORDER_CREATED,
                orderNo, 42L, AMOUNT, "KRW",
                List.of(new OrderLine(1L, "로스트아크 디럭스 패키지", 1L, AMOUNT, 1)));
        paymentService.prepare(orderNo, "CARD");
        paymentService.handleApproval(new PgApproval(orderNo, "PG-TX-" + orderNo, AMOUNT, "IDEM-" + orderNo));
        return orderNo;
    }

    /**
     * PG 호출에서 멈춘 상태를 만든다.
     *
     * <p>DB 를 손으로 CANCELING 으로 바꾸지 않는다 — 그러면 "그 상태가 실제로 만들어지는가" 가
     * 검증에서 빠진다. <b>PG 를 터뜨려 진짜로 그 자리에서 멈추게 한다.</b>
     */
    private String strandedByPgFailure() {
        String orderNo = paidPayment();
        doThrow(new IllegalStateException("PG 응답 없음"))
                .when(pgClient).cancel(anyString(), anyLong(), anyString());
        try {
            refundFacade.refund(orderNo, "USER_REFUND");
        } catch (Exception expected) {
            // 여기서 멈춘 것이 이 테스트의 전제다.
        }
        reset(pgClient);
        return orderNo;
    }

    private List<String> eventTypesOf(String orderNo) {
        return outboxEventRepository.findAll().stream()
                .filter(event -> orderNo.equals(event.getAggregateId()))
                .map(OutboxEvent::getEventType)
                .toList();
    }

    private PaymentStatus statusOf(String orderNo) {
        return paymentRepository.findByOrderNo(orderNo).orElseThrow().getStatus();
    }

    @Test
    @DisplayName("PG 호출이 실패하면 CANCELING 으로 남는다 — 돈이 나갔는지 불확실한 상태")
    void pgFailureLeavesPaymentStranded() {
        String orderNo = strandedByPgFailure();

        assertThat(statusOf(orderNo)).isEqualTo(PaymentStatus.CANCELING);
        assertThat(eventTypesOf(orderNo))
                .as("확정되지 않았으므로 PaymentCancelled 도 나가지 않았다")
                .containsExactly(EventType.PAYMENT_COMPLETED);
    }

    @Test
    @DisplayName("재개가 그 건을 확정까지 보낸다 — 예전에는 사람이 누를 때까지 그대로였다")
    void resumeFinishesStrandedCancellation() {
        String orderNo = strandedByPgFailure();

        int resumed = refundFacade.resumeStranded(IMMEDIATELY);

        assertThat(resumed).isEqualTo(1);
        assertThat(statusOf(orderNo)).isEqualTo(PaymentStatus.CANCELED);
        assertThat(eventTypesOf(orderNo)).contains(EventType.PAYMENT_CANCELLED);
        verify(pgClient).cancel(eq("PG-TX-" + orderNo), eq(AMOUNT), eq("USER_REFUND"));
    }

    /**
     * 사유를 새로 지어내면 이력이 끊긴다 — "사용자 환불" 로 시작한 건이
     * 재개 뒤에 "시스템 재시도" 로 남으면 정산·CS 가 원인을 되짚을 수 없다.
     */
    @Test
    @DisplayName("재개는 착수할 때의 사유를 그대로 이어 쓴다")
    void resumeKeepsTheOriginalReason() {
        String orderNo = strandedByPgFailure();

        refundFacade.resumeStranded(IMMEDIATELY);

        verify(pgClient).cancel(anyString(), anyLong(), eq("USER_REFUND"));
        assertThat(paymentRepository.findByOrderNo(orderNo).orElseThrow().getCancelReason())
                .isEqualTo("USER_REFUND");
    }

    @Test
    @DisplayName("확정까지 끝난 건은 다시 건드리지 않는다 — 재개가 이중 호출이 되지 않는다")
    void completedCancellationIsNotResumed() {
        String orderNo = paidPayment();
        refundFacade.refund(orderNo, "USER_REFUND");
        reset(pgClient);

        int resumed = refundFacade.resumeStranded(IMMEDIATELY);

        assertThat(resumed).isZero();
        verify(pgClient, never()).cancel(anyString(), anyLong(), anyString());
        assertThat(statusOf(orderNo)).isEqualTo(PaymentStatus.CANCELED);
    }

    /**
     * 한 건의 실패가 다음 건을 막으면, PG 가 죽어 있을 때 <b>시도조차 안 된 것</b>과
     * <b>시도했는데 실패한 것</b>이 구분되지 않는다.
     */
    @Test
    @DisplayName("한 건이 실패해도 나머지는 계속 시도한다")
    void oneFailureDoesNotBlockTheRest() {
        String failing = strandedByPgFailure();
        String recoverable = strandedByPgFailure();
        doThrow(new IllegalStateException("PG 여전히 응답 없음"))
                .when(pgClient).cancel(eq("PG-TX-" + failing), anyLong(), anyString());

        int resumed = refundFacade.resumeStranded(IMMEDIATELY);

        assertThat(resumed).as("실패한 한 건을 뺀 나머지").isEqualTo(1);
        assertThat(statusOf(failing)).isEqualTo(PaymentStatus.CANCELING);
        assertThat(statusOf(recoverable)).isEqualTo(PaymentStatus.CANCELED);
    }

    /**
     * 방금 착수한 건까지 집으면 <b>정상 진행 중인 환불을 옆에서 한 번 더 부른다.</b>
     * 멱등이라 사고는 아니지만, PG 호출이 두 배가 되고 "몇 번 시도했나" 가 흐려진다.
     */
    @Test
    @DisplayName("착수한 지 얼마 안 된 건은 집지 않는다 — 진행 중인 환불을 옆에서 부르지 않는다")
    void freshCancellationIsLeftAlone() {
        String orderNo = strandedByPgFailure();

        int resumed = refundFacade.resumeStranded(Duration.ofMinutes(10));

        assertThat(resumed).isZero();
        assertThat(statusOf(orderNo)).isEqualTo(PaymentStatus.CANCELING);
        verify(pgClient, never()).cancel(anyString(), anyLong(), anyString());
    }
}
