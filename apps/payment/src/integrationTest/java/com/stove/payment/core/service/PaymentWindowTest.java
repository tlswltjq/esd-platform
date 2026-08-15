package com.stove.payment.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

import com.stove.common.core.error.BusinessException;
import com.stove.common.core.error.ErrorCode;
import com.stove.common.event.EventType;
import com.stove.common.event.payload.OrderLine;
import com.stove.common.testcontainers.InfraContainers;
import com.stove.payment.core.domain.PaymentRepository;
import com.stove.payment.core.domain.PaymentStatus;
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
 * [D-029] <b>주문 금액이 서버 가격으로 확정되는 것은 주문을 만드는 순간 한 번뿐이다.</b>
 * 그 뒤 단계는 전부 "아까 정한 금액과 같은가"만 본다 — 사전등록도, 승인 콜백도,
 * 라이선스 지급도 아무도 catalog 에 다시 묻지 않는다.
 *
 * <p>그래서 만료가 없으면 <b>옛 가격이 영원히 유효하다.</b> 전체 스택에서 실측했다 —
 * 39,000원짜리 주문을 만들고, 상품 가격을 78,000원으로 올리고, 주문의 나이를 30일로
 * 되돌린 뒤 결제했더니 <b>39,000원으로 승인되고 라이선스까지 나갔다.</b>
 *
 * <p>여기서는 그 실측의 결정적인 부분만 다시 만든다 — 나이를 되돌리는 방식까지 같다.
 * 시간을 흉내 낸 {@code Clock} 대역이 아니라 <b>DB 의 {@code created_at} 을 직접</b> 되돌린다:
 * 만료 판정의 기준이 그 컬럼이고, 대역으로 바꾸면 정작 그 컬럼이 기준인지가 검증되지 않는다.
 */
@SpringBootTest(properties = {"stove.outbox.relay-enabled=false", "stove.payment.window=30m"})
@Import({InfraContainers.MySql.class, InfraContainers.Kafka.class})
class PaymentWindowTest {

    @Autowired
    PaymentService paymentService;
    @Autowired
    PaymentRepository paymentRepository;
    @Autowired
    JdbcTemplate jdbcTemplate;

    @MockitoSpyBean
    PgClient pgClient;

    @AfterEach
    void tearDown() {
        reset(pgClient);
    }

    private String readyPayment() {
        String orderNo = "ORD-" + UUID.randomUUID();
        paymentService.createReady(UUID.randomUUID().toString(), EventType.ORDER_CREATED,
                orderNo, 42L, 39_000L, "KRW",
                List.of(new OrderLine(1L, "로스트아크 디럭스 패키지", 1L, 39_000L, 1)));
        return orderNo;
    }

    /** 결제 대기 행을 그만큼 늙힌다. 실측에서 쓴 것과 같은 수단이다. */
    private void ageBy(String orderNo, int minutes) {
        jdbcTemplate.update(
                "update payment set created_at = created_at - interval ? minute where order_no = ?",
                minutes, orderNo);
    }

    @Test
    @DisplayName("창 안의 주문은 그대로 결제할 수 있다 — 만료 검사가 정상 경로를 막지 않는다")
    void freshOrderPreparesNormally() {
        String orderNo = readyPayment();

        assertThatCode(() -> paymentService.prepare(orderNo, "CARD")).doesNotThrowAnyException();
        assertThat(paymentRepository.findByOrderNo(orderNo).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    @DisplayName("[D-029] 창 밖의 주문은 결제를 시작할 수 없다 — 옛 가격이 영원히 유효하지 않다")
    void staleOrderCannotStartPayment() {
        String orderNo = readyPayment();
        ageBy(orderNo, 31);

        assertThatThrownBy(() -> paymentService.prepare(orderNo, "CARD"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.PAYMENT_WINDOW_EXPIRED);
    }

    /**
     * 만료를 PG 호출 <b>뒤</b>에 검사하면 우리 장부에는 없는 PG 거래가 하나 생긴다.
     * 대사에서 원인을 알 수 없는 잔여로 남으므로, 순서 자체가 계약이다.
     */
    @Test
    @DisplayName("[D-029] 만료된 주문으로는 PG 거래가 생기지 않는다 — 검사가 호출보다 앞이다")
    void staleOrderNeverReachesPg() {
        String orderNo = readyPayment();
        ageBy(orderNo, 31);

        assertThatThrownBy(() -> paymentService.prepare(orderNo, "CARD"))
                .isInstanceOf(BusinessException.class);

        verify(pgClient, never()).prepare(anyString(), anyLong(), anyString(), anyString());
    }

    /**
     * 승인은 {@code PENDING} 에서만 열린다. 사전등록이 막히면 승인 경로도 함께 닫히므로,
     * 만료 검사를 사전등록 한 곳에만 두는 것으로 <b>돈이 움직이는 길 전체</b>가 닫힌다.
     */
    @Test
    @DisplayName("[D-029] 사전등록이 막히면 상태가 READY 에 머물러 승인도 열리지 않는다")
    void staleOrderStaysReadySoApprovalStaysClosed() {
        String orderNo = readyPayment();
        ageBy(orderNo, 31);

        assertThatThrownBy(() -> paymentService.prepare(orderNo, "CARD"))
                .isInstanceOf(BusinessException.class);

        assertThat(paymentRepository.findByOrderNo(orderNo).orElseThrow().getStatus())
                .as("PENDING 이 되면 승인 콜백이 열린다")
                .isEqualTo(PaymentStatus.READY);
    }
}
