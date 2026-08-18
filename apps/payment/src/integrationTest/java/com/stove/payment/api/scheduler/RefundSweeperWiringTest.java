package com.stove.payment.api.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import com.stove.common.event.EventType;
import com.stove.common.event.payload.OrderLine;
import com.stove.common.testcontainers.InfraContainers;
import com.stove.payment.api.application.RefundFacade;
import com.stove.payment.core.domain.PaymentPreparation;
import com.stove.payment.core.domain.PaymentRepository;
import com.stove.payment.core.domain.PaymentStatus;
import com.stove.payment.core.domain.PgApproval;
import com.stove.payment.core.port.PgClient;
import com.stove.payment.core.service.PaymentService;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * 시나리오 R-04 — <b>취소 도중 프로세스가 중단되고, 재기동한 프로세스가 스스로 이어서 끝낸다.</b>
 *
 * <p>{@link com.stove.payment.core.service.StrandedRefundResumeTest} 가 이미 재개를 검증한다.
 * 다만 그쪽은 {@code refundFacade.resumeStranded()} 를 <b>테스트가 직접 부른다.</b>
 * 그래서 그 테스트는 "재개 로직이 맞는가" 까지만 말하고,
 * <b>"재기동한 프로세스에서 그걸 부르는 주체가 있는가" 는 말하지 않는다.</b>
 *
 * <p>그 차이는 침묵으로 나타난다. {@code @EnableScheduling} 이 빠지거나, ShedLock 테이블
 * 마이그레이션(V7)이 유실되거나, 프로퍼티 이름이 어긋나면 스윕은 <b>예외 하나 없이</b> 영영 돌지 않는다.
 * 그런데 이 경로는 사람이 다시 누르는 경로가 아니다 —
 * {@link RefundSweeper} 주석이 적어 둔 대로 <b>"자동 환불은 아무도 다시 누르지 않는다."</b>
 * 즉 배선이 끊기면 돈이 나갔는지 불확실한 건이 조용히 쌓인다.
 *
 * <p><b>여기서는 아무것도 직접 부르지 않는다.</b> 중단된 취소를 하나 만들어 두고 기다리기만 한다.
 * 상태가 바뀌면 그것을 바꾼 것은 스케줄러뿐이다.
 */
@SpringBootTest(properties = {
        "stove.outbox.relay-enabled=false",
        // 중단된 건을 즉시 재개 대상으로 본다 — 이 테스트가 재는 것은 시간 예산이 아니라 배선이다.
        "stove.payment.refund-resume-after=0s",
        "stove.payment.refund-sweep-interval-ms=300"
})
@Import({InfraContainers.MySql.class, InfraContainers.Kafka.class})
class RefundSweeperWiringTest {

    private static final long AMOUNT = 39_000L;

    /**
     * 스윕이 한 번 더 돌기를 기다리는 창.
     *
     * <p>주기(300ms)로 정해지지 않는다 — {@code @SchedulerLock(lockAtLeastFor = "PT30S")} 가
     * 실행 간격의 하한이기 때문이다. 컨텍스트가 뜨자마자 빈 회차가 한 번 돌면서 락을 30초 쥐므로,
     * 그 뒤에 만든 건은 <b>아무리 주기를 줄여도</b> 30초 뒤 회차에서야 잡힌다.
     * 90초는 그 한 회차를 확실히 포함하는 값이다.
     */
    private static final Duration SWEEP_WINDOW = Duration.ofSeconds(90);

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

    /**
     * PG 호출 자리에서 진짜로 멈춘 건을 만든다.
     *
     * <p>DB 를 손으로 {@code CANCELING} 으로 바꾸지 않는다 — 그러면 그 상태가 실제로 만들어지는가가
     * 검증에서 빠진다({@code StrandedRefundResumeTest} 와 같은 판단이다).
     */
    private String strandedByPgFailure() {
        String orderNo = paidPayment();
        doThrow(new IllegalStateException("PG 응답 없음"))
                .when(pgClient).cancel(anyString(), anyLong(), anyString());
        try {
            refundFacade.refund(orderNo, "USER_REFUND");
        } catch (Exception expected) {
            // 여기서 멈춘 것이 이 시나리오의 전제다 — 프로세스가 죽은 자리와 같다.
        }
        reset(pgClient);
        return orderNo;
    }

    private PaymentStatus statusOf(String orderNo) {
        return paymentRepository.findByOrderNo(orderNo).orElseThrow().getStatus();
    }

    @Test
    @DisplayName("중단된 취소를 스케줄러가 스스로 확정까지 보낸다 — 아무도 재개를 부르지 않았는데도")
    void schedulerResumesStrandedCancellationWithoutAnyoneCallingIt() {
        String orderNo = strandedByPgFailure();

        assertThat(statusOf(orderNo))
                .as("전제 — 돈이 나갔는지 불확실한 상태로 남아 있다")
                .isEqualTo(PaymentStatus.CANCELING);

        Awaitility.await("스케줄러의 재개")
                .atMost(SWEEP_WINDOW)
                .pollInterval(Duration.ofSeconds(1))
                .pollDelay(Duration.ZERO)
                .untilAsserted(() -> assertThat(statusOf(orderNo))
                        .as("""
                                이 테스트는 resumeStranded 를 부르지 않는다.
                                상태가 바뀌었다면 바꾼 것은 @Scheduled + @SchedulerLock 배선뿐이다.
                                CANCELING 그대로라면 스윕이 한 번도 돌지 않은 것이다 —
                                @EnableScheduling · ShedLock 테이블(V7) · 프로퍼티 이름 순으로 본다.""")
                        .isEqualTo(PaymentStatus.CANCELED));
    }
}
