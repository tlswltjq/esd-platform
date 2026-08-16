package com.stove.payment.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

import com.stove.common.event.EventType;
import com.stove.common.event.payload.OrderLine;
import com.stove.common.messaging.outbox.OutboxEventRepository;
import com.stove.common.testcontainers.InfraContainers;
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

/**
 * Saga 보상 환불 경로가 <b>살아 있는가</b>.
 *
 * <p><b>왜 이 테스트가 따로 필요한가</b> — 이 경로는 운영에서 한 번도 지나가지 않았다.
 * 실측으로 license outbox 4,153건 중 {@code LicenseIssueFailed} 는 <b>0건</b>이다.
 * D-027 이 보상 조건을 "재시도 소진 + 저장소 탓이 아님 + 실제로 지급되지 않음" 으로 좁혔고,
 * 저장소 장애는 전부 DLT 로 가기 때문이다(chaos 2회차 실측: 보류 47 · 환불 0).
 *
 * <p>그 상태에서 <b>배선이 끊겨도 아무도 모른다.</b> 지금까지 이 경로를 지키는 것은 단위 테스트
 * 하나뿐이었고(모의 객체 위에서 돈다), e2e 는 이 경로를 지나지 않는다 — D-030 이 "회귀 테스트가
 * 없는 경로를 지키고 있었다" 로 드러낸 것과 같은 자리다.
 *
 * <p>그래서 <b>실 MySQL 위에서</b> 보상 진입점을 지난다. 사용자 환불과 규칙은 같지만
 * 진입점이 다르고(멱등 가드), 그 차이가 여기서 검증할 성질이다.
 *
 * <p>{@code #46} 이 "남긴다면 그 경로가 살아 있다는 것을 무엇이 보장하는가" 라고 물었고,
 * <b>이 파일이 그 답이다.</b>
 */
@SpringBootTest(properties = "stove.outbox.relay-enabled=false")
@Import({InfraContainers.MySql.class, InfraContainers.Kafka.class})
class PaymentCompensationTest {

    @Autowired
    PaymentService paymentService;
    @Autowired
    RefundFacade refundFacade;
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
    @DisplayName("보상 진입점이 결제를 되돌리고 PaymentCancelled 를 적재한다")
    void compensationRefundsAndRecordsEvent() {
        String orderNo = paidOrder(30_000L);
        long before = outboxEventRepository.count();

        refundFacade.compensate(UUID.randomUUID().toString(), EventType.LICENSE_ISSUE_FAILED,
                orderNo, "LICENSE_ISSUE_FAILED:지급 실패");

        assertThat(statusOf(orderNo)).isEqualTo(PaymentStatus.CANCELED);
        verify(pgClient).cancel(anyString(), anyLong(), anyString());
        assertThat(outboxEventRepository.count() - before).isEqualTo(1);
    }

    /**
     * 보상은 이벤트로 들어오므로 <b>같은 이벤트가 다시 올 수 있다.</b> 두 번째 배달이 PG 를 다시
     * 부르면 이중 환불이고, 그것을 막는 것이 멱등 가드다 — 사용자 환불 경로에는 없는 장치라
     * 진입점을 나눠 둔 이유가 여기 있다.
     */
    @Test
    @DisplayName("같은 이벤트가 다시 와도 PG 를 다시 부르지 않는다 — 멱등 가드")
    void duplicateEventDoesNotRefundTwice() {
        String orderNo = paidOrder(30_000L);
        String eventId = UUID.randomUUID().toString();

        refundFacade.compensate(eventId, EventType.LICENSE_ISSUE_FAILED, orderNo, "reason");
        reset(pgClient);
        refundFacade.compensate(eventId, EventType.LICENSE_ISSUE_FAILED, orderNo, "reason");

        verify(pgClient, never()).cancel(anyString(), anyLong(), anyString());
        assertThat(statusOf(orderNo)).isEqualTo(PaymentStatus.CANCELED);
    }

    /**
     * 결제가 없는 주문번호로 보상이 들어오는 경우.
     *
     * <p><b>예외를 던지면 안 된다.</b> 던지면 멱등 가드 마킹까지 롤백되어 같은 이벤트가 영원히
     * 재전송되고, 블로킹 재시도라 그 파티션이 멈춘다(D-018). 조용히 넘기되 로그로 남기는 것이
     * {@code PaymentService#beginCompensation} 의 판단이고, 그 판단이 유지되는지를 여기서 지킨다.
     */
    @Test
    @DisplayName("결제가 없는 주문의 보상은 예외 없이 넘어간다 — 무한 재배달 방지")
    void compensationForUnknownOrderDoesNotThrow() {
        refundFacade.compensate(UUID.randomUUID().toString(), EventType.LICENSE_ISSUE_FAILED,
                "ORD-NOT-EXIST-" + UUID.randomUUID(), "reason");

        verify(pgClient, never()).cancel(anyString(), anyLong(), anyString());
    }
}
