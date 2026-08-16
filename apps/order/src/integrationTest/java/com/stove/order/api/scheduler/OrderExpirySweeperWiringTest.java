package com.stove.order.api.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import com.stove.common.event.payload.OrderLine;
import com.stove.common.testcontainers.InfraContainers;
import com.stove.order.core.domain.Order;
import com.stove.order.core.domain.OrderRepository;
import com.stove.order.core.domain.OrderStatus;
import com.stove.order.core.service.OrderCommandService;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 시나리오 R-05 — <b>order 가 중단된 사이 만료 시각이 지나고, 재기동한 프로세스가 밀린 것을 닫는다.</b>
 *
 * <p>만료는 이 시스템에서 <b>사건이 없는 자리를 시간이 대신하는</b> 유일한 경로다
 * ({@link com.stove.order.core.service.OrderExpiryService} 주석). 결제를 시작하지 않은 주문은
 * 아무 이벤트도 낳지 않으므로, 스윕이 돌지 않으면 <b>그 주문을 닫아 줄 다른 장치가 없다.</b>
 *
 * <p>{@code OrderExpiryServiceTest} 는 {@code expireStaleOrders()} 를 직접 부른다 —
 * "닫는 규칙이 맞는가" 는 그쪽이 지킨다. 여기서 묻는 것은 다른 것이다:
 * <b>재기동한 프로세스에서 그 메서드를 부르는 주체가 실제로 도는가.</b>
 *
 * <p>배선이 끊겨도 아무것도 빨개지지 않는다는 점이 이 시나리오의 핵심이다. 만료는 예외를 던지지
 * 않고, 실패 지표도 없다 — 그냥 {@code CREATED} 가 늘어난다. 실측 98,750건이 그렇게 쌓였다(#43).
 * <b>침묵으로 실패하는 경로는 침묵을 깨는 테스트가 필요하다.</b>
 */
@SpringBootTest(properties = {
        "stove.outbox.relay-enabled=false",
        "stove.order.expire-after=1h",
        "stove.order.expire-sweep-interval-ms=300"
})
@Import({InfraContainers.MySql.class, InfraContainers.Kafka.class})
class OrderExpirySweeperWiringTest {

    private static final Long MEMBER = 42L;

    /**
     * {@code @SchedulerLock(lockAtLeastFor = "PT30S")} 가 실행 간격의 하한이라
     * 주기(300ms)로는 정해지지 않는다 — 컨텍스트 기동 직후의 빈 회차가 락을 30초 쥔다.
     */
    private static final Duration SWEEP_WINDOW = Duration.ofSeconds(90);

    @Autowired
    OrderCommandService orderCommandService;
    @Autowired
    OrderRepository orderRepository;
    @Autowired
    JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("delete from order_item");
        jdbcTemplate.update("delete from orders");
        jdbcTemplate.update("delete from outbox_event");
    }

    /**
     * 중단된 사이에 창을 넘긴 주문.
     *
     * <p>생성은 실제 경로로 하고 시각만 SQL 로 당긴다 — 상태를 손으로 심으면
     * "그 상태가 실제로 만들어지는가" 가 검증에서 빠진다.
     */
    private Order strandedByOutage() {
        Order order = orderCommandService.createOrder(
                MEMBER, "KRW", List.of(new OrderLine(1L, "로스트아크", 1001L, 39_000L, 1)));
        jdbcTemplate.update("update orders set created_at = ? where order_no = ?",
                Instant.now().minus(3, ChronoUnit.HOURS), order.getOrderNo());
        return order;
    }

    private OrderStatus statusOf(String orderNo) {
        return orderRepository.findByOrderNo(orderNo).orElseThrow().getStatus();
    }

    @Test
    @DisplayName("중단 중 창을 넘긴 주문을 스케줄러가 스스로 닫는다 — 아무도 스윕을 부르지 않았는데도")
    void schedulerExpiresStaleOrdersWithoutAnyoneCallingIt() {
        Order order = strandedByOutage();

        assertThat(statusOf(order.getOrderNo()))
                .as("전제 — 상태를 바꿔 줄 사건이 없는 주문이다")
                .isEqualTo(OrderStatus.CREATED);

        Awaitility.await("스케줄러의 만료 스윕")
                .atMost(SWEEP_WINDOW)
                .pollInterval(Duration.ofSeconds(1))
                .pollDelay(Duration.ZERO)
                .untilAsserted(() -> assertThat(statusOf(order.getOrderNo()))
                        .as("""
                                이 테스트는 expireStaleOrders 를 부르지 않는다.
                                CREATED 그대로라면 스윕이 한 번도 돌지 않은 것이고,
                                그 실패는 예외도 지표도 남기지 않는다 — 그래서 여기서 잡아야 한다.""")
                        .isEqualTo(OrderStatus.EXPIRED));
    }
}
