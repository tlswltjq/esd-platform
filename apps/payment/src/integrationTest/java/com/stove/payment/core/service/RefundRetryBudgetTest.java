package com.stove.payment.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

import com.stove.common.event.EventType;
import com.stove.common.event.payload.OrderLine;
import com.stove.common.testcontainers.InfraContainers;
import com.stove.payment.api.application.RefundFacade;
import com.stove.payment.core.domain.Payment;
import com.stove.payment.core.domain.PaymentPreparation;
import com.stove.payment.core.domain.PaymentRepository;
import com.stove.payment.core.domain.PaymentStatus;
import com.stove.payment.core.domain.PgApproval;
import com.stove.payment.core.domain.RefundRetryPolicy;
import com.stove.payment.core.port.PgClient;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * 중단된 취소 재개에 <b>시간 예산</b>이 있는가.
 *
 * <p>재개를 넣은 회차는 "조건 없이 계속 재시도" 였다. PG 가 오래 죽어 있으면 같은 건에
 * 1분마다 요청이 나갔고 <b>복구 중인 PG 를 계속 두드렸다</b>. 멈추는 것은 알람뿐이었는데
 * 알람은 사람을 부를 뿐 재시도를 멈추지 않는다(#41).
 *
 * <p>여기서 지키는 성질 셋.
 * <ol>
 *   <li>착수 직후에는 집지 않는다 — 진행 중인 환불을 옆에서 부르지 않는다</li>
 *   <li>실패하면 다음 시도가 <b>미뤄진다</b> — 곧바로 다시 잡히지 않는다</li>
 *   <li>시도 횟수가 <b>행에 남는다</b> — 로그를 세지 않아도 "몇 번째인가" 를 물을 수 있다</li>
 * </ol>
 *
 * <p><b>포기 상태는 검증하지 않는다. 만들지 않았기 때문이다.</b> Outbox 는 예산이 소진되면
 * {@code DEAD} 로 보내지만(D-003), {@code CANCELING} 은 "돈이 나갔는지 불확실" 이라는 뜻이라
 * 포기할 대상이 아니다 — 종단 상태로 옮기는 순간 불확실이 해소된 것처럼 보이고 아무도 다시 보지 않는다.
 * 예산은 재시도를 멈추는 값이 아니라 <b>사람을 부르는 값</b>이고, 그쪽은 게이지가 맡는다.
 */
@SpringBootTest(properties = {
        "stove.outbox.relay-enabled=false",
        // 기본값(2분)을 그대로 쓴다. **0s 로 낮추면 "착수 직후에는 집지 않는다" 를 잴 수 없다** —
        // 그게 이 클래스가 StrandedRefundResumeTest 와 따로 있는 이유다.
        "stove.payment.refund-resume-after=2m"
})
@Import({InfraContainers.MySql.class, InfraContainers.Kafka.class})
class RefundRetryBudgetTest {

    private static final long AMOUNT = 39_000L;

    @Autowired
    PaymentService paymentService;
    @Autowired
    RefundFacade refundFacade;
    @Autowired
    PaymentRepository paymentRepository;

    @MockitoSpyBean
    PgClient pgClient;

    @AfterEach
    void tearDown() {
        reset(pgClient);
    }

    private String paidPayment() {
        String orderNo = "ORD-" + UUID.randomUUID();
        paymentService.createReady(UUID.randomUUID().toString(), EventType.ORDER_CREATED,
                orderNo, 42L, AMOUNT, "KRW",
                List.of(new OrderLine(1L, "게임 A", 1001L, AMOUNT, 1)));
        PaymentPreparation prepared = paymentService.prepare(orderNo, "CARD");
        paymentService.handleApproval(new PgApproval(orderNo, prepared.pgTxId(), AMOUNT,
                "PGKEY-" + UUID.randomUUID()));
        return orderNo;
    }

    /** PG 를 터뜨려 진짜로 그 자리에서 멈추게 한다 — DB 를 손으로 바꾸면 그 상태가 만들어지는지가 빠진다. */
    private String strandedByPgFailure() {
        String orderNo = paidPayment();
        doThrow(new IllegalStateException("PG 응답 없음"))
                .when(pgClient).cancel(anyString(), anyLong(), anyString());
        try {
            refundFacade.refund(orderNo, "USER_REFUND");
        } catch (Exception expected) {
            // 여기서 멈춘 것이 전제다.
        }
        reset(pgClient);
        return orderNo;
    }

    private Payment reload(String orderNo) {
        return paymentRepository.findByOrderNo(orderNo).orElseThrow();
    }

    @Test
    @DisplayName("착수한 지 얼마 안 된 건은 집지 않는다 — 진행 중인 환불을 옆에서 부르지 않는다")
    void freshCancellationIsLeftAlone() {
        String orderNo = strandedByPgFailure();

        int resumed = refundFacade.resumeStranded();

        assertThat(resumed).isZero();
        assertThat(reload(orderNo).getStatus()).isEqualTo(PaymentStatus.CANCELING);
        verify(pgClient, never()).cancel(anyString(), anyLong(), anyString());
    }

    @Test
    @DisplayName("착수하면 다음 시도 시각이 예약되고 시도 횟수는 0 이다 — 아직 아무것도 시도하지 않았다")
    void firstScheduleDoesNotCountAsAnAttempt() {
        String orderNo = strandedByPgFailure();

        Payment payment = reload(orderNo);
        assertThat(payment.getNextCancelAttemptAt())
                .as("착수 직후 유예가 예약된다").isNotNull();
        assertThat(payment.getCancelAttempts())
                .as("예약은 시도가 아니다 — 여기서 올리면 첫 재개가 2회차로 기록된다").isZero();
        assertThat(payment.getCancelingSince()).isNotNull();
    }

    /**
     * 예산 초과 판정이 {@code cancelingSince} 를 보는지 확인한다.
     *
     * <p>{@code updatedAt} 을 쓰면 <b>재시도할 때마다 갱신되어 예산이 영원히 안 찬다</b> —
     * 재시도가 잦을수록 사람을 늦게 부르게 되는데, 그건 정확히 반대 방향이다.
     */
    @Test
    @DisplayName("예산 초과는 착수 시각으로 잰다 — 재시도가 그 시계를 되돌리지 않는다")
    void budgetIsMeasuredFromCancelStart() {
        String orderNo = strandedByPgFailure();
        Payment payment = reload(orderNo);

        assertThat(payment.cancelBudgetExceeded(Duration.ofHours(1), Instant.now()))
                .as("방금 착수했으므로 아직 예산 안").isFalse();
        assertThat(payment.cancelBudgetExceeded(Duration.ofHours(1), Instant.now().plus(Duration.ofHours(2))))
                .as("두 시간 뒤에는 예산을 넘긴다").isTrue();
    }

    /**
     * 백오프가 실제로 커지는가. 상한이 없으면 24시간짜리 대기가 생기고,
     * 그 사이 PG 가 복구돼도 우리는 모른다.
     */
    @Test
    @DisplayName("백오프는 2분에서 시작해 30분에서 멈춘다")
    void backoffGrowsAndCaps() {
        assertThat(RefundRetryPolicy.backoffAfter(0)).isEqualTo(Duration.ofMinutes(2));
        assertThat(RefundRetryPolicy.backoffAfter(1)).isEqualTo(Duration.ofMinutes(4));
        assertThat(RefundRetryPolicy.backoffAfter(2)).isEqualTo(Duration.ofMinutes(8));
        assertThat(RefundRetryPolicy.backoffAfter(3)).isEqualTo(Duration.ofMinutes(16));
        assertThat(RefundRetryPolicy.backoffAfter(4)).isEqualTo(Duration.ofMinutes(30));
        assertThat(RefundRetryPolicy.backoffAfter(100))
                .as("시도가 아무리 늘어도 상한을 넘지 않는다 — 재시도가 끝나지 않는 설계라 이 값은 실제로 커진다")
                .isEqualTo(Duration.ofMinutes(30));
    }

    /**
     * 이 저장소가 반복해 밟은 자리 — <b>장치가 있다는 것과 그 장치가 그 자리를 지킨다는 것은 다르다.</b>
     * 실패한 건이 곧바로 다시 잡히면 백오프는 있으나 마나다.
     */
    @Test
    @DisplayName("재개가 실패하면 다음 시도가 미뤄지고 시도 횟수가 오른다")
    void failedResumeBacksOffAndCountsTheAttempt() {
        String orderNo = strandedByPgFailure();
        // 유예를 지난 것으로 만든다. 상태를 손으로 심는 것이 아니라 **예약 시각만** 앞당긴다 —
        // CANCELING 자체는 위에서 PG 를 터뜨려 진짜로 만들었다.
        makeDue(orderNo);
        doThrow(new IllegalStateException("PG 여전히 응답 없음"))
                .when(pgClient).cancel(anyString(), anyLong(), anyString());

        int resumed = refundFacade.resumeStranded();

        assertThat(resumed).isZero();
        Payment payment = reload(orderNo);
        assertThat(payment.getCancelAttempts()).as("실패도 시도다").isEqualTo(1);
        assertThat(payment.getNextCancelAttemptAt())
                .as("다음 시도는 백오프만큼 미뤄진다 — 곧바로 다시 잡히면 백오프가 없는 것과 같다")
                .isAfter(Instant.now().plus(Duration.ofMinutes(1)));

        reset(pgClient);
        assertThat(refundFacade.resumeStranded())
                .as("미뤄졌으므로 곧바로 다시 잡히지 않는다").isZero();
        verify(pgClient, never()).cancel(anyString(), anyLong(), anyString());
    }

    @Test
    @DisplayName("확정되면 예약이 지워진다 — 죽은 값이 인덱스에 남지 않는다")
    void completionClearsTheSchedule() {
        String orderNo = strandedByPgFailure();
        makeDue(orderNo);

        assertThat(refundFacade.resumeStranded()).isEqualTo(1);

        Payment payment = reload(orderNo);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELED);
        assertThat(payment.getNextCancelAttemptAt()).isNull();
    }

    /** 예약 시각만 과거로 당긴다. 스윕이 "지금 집어야 할 것" 으로 보게 만드는 최소한의 조작이다. */
    private void makeDue(String orderNo) {
        Payment payment = reload(orderNo);
        payment.scheduleFirstCancelRetry(Duration.ofMinutes(-5));
        paymentRepository.saveAndFlush(payment);
    }
}
