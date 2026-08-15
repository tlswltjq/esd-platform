package com.stove.payment.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

import com.stove.common.event.EventType;
import com.stove.common.event.payload.OrderLine;
import com.stove.common.messaging.outbox.OutboxEvent;
import com.stove.common.messaging.outbox.OutboxEventRepository;
import com.stove.common.testcontainers.InfraContainers;
import com.stove.payment.api.application.PaymentCallbackFacade;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * 결제창이 만료된 뒤 도착한 승인 — <b>거절하지 않고 받아 적은 뒤 자동 환불한다.</b>
 *
 * <p>[D-029](../../../../../../../../docs/defects.md) 가 주문 창을 닫으면서 남겨 둔 자리다.
 * 사전등록까지는 창 안이었는데 사용자가 결제창을 며칠 열어 둔 경우가 여기 걸린다.
 *
 * <p><b>왜 거절하지 않는가</b> — 승인 콜백이 도착한 시점에는 PG 에서 이미 돈이 움직였다.
 * 예외를 던져 거절하면 우리 장부에만 없는 상태가 되어 <b>대사에서 원인을 알 수 없는 잔여</b>가 된다.
 * 그건 막으려던 문제를 다른 문제로 바꾼 것이지 없앤 것이 아니다.
 *
 * <p><b>왜 {@code PaymentCompleted} 를 내보내지 않는가</b> — 내보내면 license 가 지급하고
 * settlement 가 매출을 적은 뒤 곧이어 둘 다 되돌린다. 사용자에게는 게임이 잠깐 생겼다 사라지고
 * 원장에는 매출과 상계가 한 쌍 남는다. <b>일어나지 않을 판매는 알리지 않는다.</b>
 * 그 성질을 {@code publishesNoSaleForAnOrderThatWillBeRefunded} 가 고정한다.
 */
@SpringBootTest(properties = {
        "stove.outbox.relay-enabled=false",
        "stove.payment.window=30m",
        "stove.payment.checkout-window=15m"
})
@Import({InfraContainers.MySql.class, InfraContainers.Kafka.class})
class PaymentCheckoutWindowTest {

    private static final long AMOUNT = 39_000L;

    @Autowired
    PaymentService paymentService;
    @Autowired
    PaymentCallbackFacade paymentCallbackFacade;
    @Autowired
    PaymentRepository paymentRepository;
    @Autowired
    OutboxEventRepository outboxEventRepository;
    @Autowired
    JdbcTemplate jdbcTemplate;

    @MockitoSpyBean
    PgClient pgClient;

    @AfterEach
    void tearDown() {
        reset(pgClient);
    }

    /** 사전등록까지 마친 결제 하나를 만든다 — 여기까지는 창 안이다. */
    private String preparedPayment() {
        String orderNo = "ORD-" + UUID.randomUUID();
        paymentService.createReady(UUID.randomUUID().toString(), EventType.ORDER_CREATED,
                orderNo, 42L, AMOUNT, "KRW",
                List.of(new OrderLine(1L, "로스트아크 디럭스 패키지", 1L, AMOUNT, 1)));
        paymentService.prepare(orderNo, "CARD");
        return orderNo;
    }

    /** 결제창을 연 지 그만큼 지난 상태로 만든다. 실측에서 쓴 것과 같은 수단이다. */
    private void checkoutOpenedAgo(String orderNo, int minutes) {
        jdbcTemplate.update(
                "update payment set prepared_at = prepared_at - interval ? minute where order_no = ?",
                minutes, orderNo);
    }

    private void approve(String orderNo) {
        paymentCallbackFacade.approve(new PgApproval(orderNo, "PG-TX-" + orderNo, AMOUNT, "IDEM-" + orderNo));
    }

    private List<String> eventTypesOf(String orderNo) {
        return outboxEventRepository.findAll().stream()
                .filter(event -> orderNo.equals(event.getAggregateId()))
                .map(OutboxEvent::getEventType)
                .toList();
    }

    @Test
    @DisplayName("창 안에 도착한 승인은 그대로 확정된다 — 만료 검사가 정상 경로를 막지 않는다")
    void freshCheckoutApprovesNormally() {
        String orderNo = preparedPayment();

        approve(orderNo);

        assertThat(paymentRepository.findByOrderNo(orderNo).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.PAID);
        assertThat(eventTypesOf(orderNo)).containsExactly(EventType.PAYMENT_COMPLETED);
        verify(pgClient, never()).cancel(anyString(), anyLong(), anyString());
    }

    @Test
    @DisplayName("창 밖에 도착한 승인은 거절하지 않는다 — 장부에 적고 되돌린다")
    void staleCheckoutIsAcceptedThenRefunded() {
        String orderNo = preparedPayment();
        checkoutOpenedAgo(orderNo, 16);

        approve(orderNo);

        // 거절(예외)이 아니라 취소로 끝난다. 승인이 없었던 것처럼 두면 PG 에만 거래가 남는다.
        assertThat(paymentRepository.findByOrderNo(orderNo).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.CANCELED);
        verify(pgClient).cancel(eq("PG-TX-" + orderNo), eq(AMOUNT), eq(PaymentService.CHECKOUT_EXPIRED));
    }

    /**
     * 이 테스트가 이 수정의 핵심이다. 상태만 보면 "취소됐다"로 같지만,
     * <b>일어나지 않을 판매를 하위 서비스에 알렸는지</b>는 이벤트를 봐야 갈린다.
     */
    @Test
    @DisplayName("되돌릴 결제의 판매는 알리지 않는다 — PaymentCompleted 없이 PaymentCancelled 만 나간다")
    void publishesNoSaleForAnOrderThatWillBeRefunded() {
        String orderNo = preparedPayment();
        checkoutOpenedAgo(orderNo, 16);

        approve(orderNo);

        assertThat(eventTypesOf(orderNo))
                .as("PaymentCompleted 가 나가면 license 가 지급하고 settlement 가 매출을 적은 뒤 되돌린다")
                .containsExactly(EventType.PAYMENT_CANCELLED);
    }

    /**
     * 사전등록을 거치지 않은 결제에는 {@code prepared_at} 이 없다.
     * 판단 근거가 없는데 '만료됨'이라고 답하면 <b>정상 결제가 자동 환불된다</b> —
     * license 의 보상 판정과 같은 규칙이다(D-027 "모르면 환불하지 않는다").
     */
    @Test
    @DisplayName("결제창을 연 기록이 없으면 만료로 보지 않는다 — 모를 때 돈을 움직이지 않는다")
    void missingPreparedAtIsNotTreatedAsExpired() {
        String orderNo = preparedPayment();
        jdbcTemplate.update("update payment set prepared_at = null where order_no = ?", orderNo);

        approve(orderNo);

        assertThat(paymentRepository.findByOrderNo(orderNo).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.PAID);
        verify(pgClient, never()).cancel(anyString(), anyLong(), anyString());
    }

    /**
     * 두 시계가 정말 나뉘어 있는지 본다. 주문 창(30분)을 거의 다 쓴 뒤 결제창을 열어도
     * 결제창 시간(15분)은 <b>거기서부터</b> 시작해야 한다 — 하나로 재면 여기서 환불된다.
     */
    @Test
    @DisplayName("[D-029 후속] 주문 창을 거의 다 쓰고 연 결제창도 제 시간을 받는다 — 시계가 둘이다")
    void checkoutClockStartsAtPrepareNotAtOrder() {
        String orderNo = preparedPayment();
        jdbcTemplate.update(
                "update payment set created_at = created_at - interval 29 minute where order_no = ?", orderNo);

        approve(orderNo);

        assertThat(paymentRepository.findByOrderNo(orderNo).orElseThrow().getStatus())
                .as("주문은 29분 됐지만 결제창은 방금 열었다")
                .isEqualTo(PaymentStatus.PAID);
    }
}
