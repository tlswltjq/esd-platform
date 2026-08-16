package com.stove.order.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stove.common.core.error.BusinessException;
import com.stove.common.event.payload.OrderLine;
import com.stove.common.messaging.outbox.OutboxEventRepository;
import com.stove.common.testcontainers.InfraContainers;
import com.stove.order.core.domain.Order;
import com.stove.order.core.domain.OrderRepository;
import com.stove.order.core.domain.OrderStatus;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 결제를 시작조차 하지 않은 주문을 닫는다.
 *
 * <p><b>왜 필요했나</b> — {@code CREATED} 이후 상태는 전부 결제 결과 이벤트로 바뀐다.
 * 결제를 아예 시작하지 않으면 그 이벤트가 없으므로 <b>상태를 바꿔 줄 사건 자체가 없었고</b>,
 * 실측으로 전체 주문의 96%(98,750 / 102,950)가 {@code CREATED} 였다(#43).
 *
 * <p>여기서 지키는 성질 넷.
 * <ol>
 *   <li>창을 넘긴 {@code CREATED} 만 만료된다</li>
 *   <li>결제가 한 걸음이라도 진행된 주문은 건드리지 않는다</li>
 *   <li>한 회차가 배치 크기를 넘지 않는다 — <b>밀린 것을 한 번에 삼키지 않는다</b></li>
 *   <li><b>이벤트를 내지 않는다</b> — 아무도 반응하지 않는 이벤트를 밀린 건수만큼 내지 않는다</li>
 * </ol>
 */
@SpringBootTest(properties = {
        "stove.outbox.relay-enabled=false",
        "stove.order.expire-after=1h",
        // 배치 상한이 실제로 지켜지는지 보려면 상한보다 많이 만들어야 한다. 작게 잡아 회차를 짧게 둔다.
        "stove.order.expire-batch-size=2"
})
@Import({InfraContainers.MySql.class, InfraContainers.Kafka.class})
class OrderExpiryServiceTest {

    private static final Long MEMBER = 42L;

    @Autowired
    OrderExpiryService orderExpiryService;
    @Autowired
    OrderCommandService orderCommandService;
    @Autowired
    OrderRepository orderRepository;
    @Autowired
    OutboxEventRepository outboxEventRepository;
    @Autowired
    JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        // 회차 사이에 이전 주문이 남으면 배치 상한 판정이 흔들린다 — 전역 건수로 세기 때문이다.
        // 이 저장소가 StrandedRefundResumeTest 에서 이미 밟은 함정이다(expected 1 / actual 2).
        jdbcTemplate.update("delete from order_item");
        jdbcTemplate.update("delete from orders");
        jdbcTemplate.update("delete from outbox_event");
    }

    private static List<OrderLine> lines() {
        return List.of(new OrderLine(1L, "로스트아크", 1001L, 39_000L, 1));
    }

    /**
     * 창을 넘긴 주문을 만든다.
     *
     * <p>{@code createdAt} 은 감사(auditing)가 채우므로 엔티티로는 뒤로 못 민다.
     * 그래서 생성은 실제 경로로 하고 <b>시각만</b> SQL 로 당긴다 — 상태를 손으로 심으면
     * "그 상태가 실제로 만들어지는가" 가 검증에서 빠진다.
     */
    private Order staleOrder() {
        Order order = orderCommandService.createOrder(MEMBER, "KRW", lines());
        jdbcTemplate.update("update orders set created_at = ? where order_no = ?",
                Instant.now().minus(3, ChronoUnit.HOURS), order.getOrderNo());
        return order;
    }

    private OrderStatus statusOf(String orderNo) {
        return orderRepository.findByOrderNo(orderNo).orElseThrow().getStatus();
    }

    @Test
    @DisplayName("창을 넘긴 미결제 주문이 EXPIRED 로 닫힌다")
    void expiresStaleCreatedOrders() {
        Order order = staleOrder();

        assertThat(orderExpiryService.expireStaleOrders()).isEqualTo(1);

        assertThat(statusOf(order.getOrderNo())).isEqualTo(OrderStatus.EXPIRED);
        assertThat(orderRepository.findByOrderNo(order.getOrderNo()).orElseThrow().getExpiredAt())
                .as("만료 대상이 된 시각이 아니라 실제로 만료시킨 시각을 남긴다").isNotNull();
    }

    @Test
    @DisplayName("아직 창 안인 주문은 건드리지 않는다")
    void freshOrderIsLeftAlone() {
        Order order = orderCommandService.createOrder(MEMBER, "KRW", lines());

        assertThat(orderExpiryService.expireStaleOrders()).isZero();

        assertThat(statusOf(order.getOrderNo())).isEqualTo(OrderStatus.CREATED);
    }

    /**
     * 결제가 한 걸음이라도 진행된 주문은 결과 이벤트가 상태를 바꾼다 — 시간이 끼어들 자리가 아니다.
     * 스윕이 그런 건을 집었다면 조회 조건이 틀린 것이고, <b>조용히 넘기면 그 사실이 묻힌다.</b>
     */
    @Test
    @DisplayName("결제가 진행된 주문은 만료 대상이 아니다 — 잘못 집으면 예외로 드러난다")
    void paidOrderIsNotExpirable() {
        Order order = staleOrder();
        order.markPaid();
        orderRepository.saveAndFlush(order);

        assertThat(orderExpiryService.expireStaleOrders()).isZero();
        assertThat(statusOf(order.getOrderNo())).isEqualTo(OrderStatus.PAID);

        assertThatThrownBy(() -> orderRepository.findByOrderNo(order.getOrderNo()).orElseThrow().expire())
                .isInstanceOf(BusinessException.class);
    }

    /**
     * 배치 상한이 없으면 첫 회차가 밀린 것을 전부 집는다 — 실측 98,750건이었고,
     * 한 트랜잭션에 넣으면 락과 언두 로그가 그만큼 커진다.
     */
    @Test
    @DisplayName("한 회차는 배치 크기를 넘지 않는다 — 나머지는 다음 회차로 넘어간다")
    void oneSweepDoesNotExceedTheBatchSize() {
        staleOrder();
        staleOrder();
        staleOrder();

        assertThat(orderExpiryService.expireStaleOrders()).as("배치 크기 2").isEqualTo(2);
        assertThat(orderRepository.countByStatus(OrderStatus.CREATED)).isEqualTo(1);

        assertThat(orderExpiryService.expireStaleOrders()).as("남은 하나").isEqualTo(1);
        assertThat(orderRepository.countByStatus(OrderStatus.CREATED)).isZero();
    }

    /**
     * D-029 가 만료 스케줄러를 한 번 버렸던 이유는 payment 가 {@code OrderCanceled} 를 듣지 않아
     * 결제 대기 레코드가 열린 채 남았기 때문이다. 지금은 그 이유가 해소됐지만
     * <b>"아무도 반응하지 않는 이벤트를 내지 않는다" 는 판단은 그대로다.</b>
     *
     * <p>밀린 것이 98,750건이면 그건 정리가 아니라 사고다 — 하위 서비스 셋이 전부
     * "되돌릴 것이 없다" 로 끝나는 메시지를 그만큼 받는다.
     */
    @Test
    @DisplayName("만료는 이벤트를 내지 않는다 — 아무도 반응하지 않는 메시지를 밀린 건수만큼 내지 않는다")
    void expiryEmitsNoEvent() {
        staleOrder();
        long before = outboxEventRepository.count();

        orderExpiryService.expireStaleOrders();

        assertThat(outboxEventRepository.count()).isEqualTo(before);
    }
}
