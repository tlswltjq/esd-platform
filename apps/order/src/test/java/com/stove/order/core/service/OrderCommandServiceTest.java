package com.stove.order.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stove.common.core.error.BusinessException;
import com.stove.common.core.error.ErrorCode;
import com.stove.common.event.EventType;
import com.stove.common.event.payload.OrderLine;
import com.stove.common.messaging.inbox.ProcessedEventRepository;
import com.stove.common.messaging.outbox.OutboxEvent;
import com.stove.common.messaging.outbox.OutboxEventRepository;
import com.stove.common.test.InfraContainers;
import com.stove.order.core.domain.Order;
import com.stove.order.core.domain.OrderRepository;
import com.stove.order.core.domain.OrderStatus;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * 주문 쓰기 트랜잭션.
 *
 * <p>이 클래스는 그동안 테스트에서 <b>mock 으로만</b> 등장했다 — 세 개의 테스트가 모두
 * {@code mock(OrderCommandService.class)} 를 쓰고 있어 구현이 한 번도 실행되지 않았다.
 * 그래서 주문의 Outbox 적재와 컨슈머 멱등 가드에 회귀 방어선이 없었다.
 *
 * <p>여기서 보는 성질은 둘이다 — <b>DB 변경과 이벤트 적재가 같은 트랜잭션에서 일어나는가</b>,
 * 그리고 <b>중복 수신이 상태를 두 번 바꾸지 않는가</b>.
 */
@SpringBootTest(properties = "stove.outbox.relay-enabled=false")
@Import({InfraContainers.MySql.class, InfraContainers.Kafka.class})
class OrderCommandServiceTest {

    private static final Long MEMBER = 42L;

    @Autowired
    OrderCommandService orderCommandService;
    @Autowired
    OrderRepository orderRepository;
    @Autowired
    OutboxEventRepository outboxEventRepository;
    @Autowired
    ProcessedEventRepository processedEventRepository;

    private static List<OrderLine> lines() {
        return List.of(new OrderLine(1L, "로스트아크", 1001L, 39_000L, 2));
    }

    private Order createOrder() {
        return orderCommandService.createOrder(MEMBER, "KRW", lines());
    }

    /** 이 주문이 적재한 이벤트만 본다 — 같은 DB 를 공유하는 다른 테스트와 섞이지 않게. */
    private List<OutboxEvent> outboxFor(String orderNo) {
        return outboxEventRepository.findAll().stream()
                .filter(e -> orderNo.equals(e.getPartitionKey()))
                .toList();
    }

    private OrderStatus statusOf(String orderNo) {
        return orderRepository.findByOrderNo(orderNo).orElseThrow().getStatus();
    }

    @Test
    @DisplayName("주문 생성은 금액을 항목 합계로 확정한다")
    void createOrderSumsLines() {
        Order order = createOrder();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CREATED);
        assertThat(order.getTotalAmount()).isEqualTo(78_000L);
        assertThat(order.getOrderNo()).startsWith("ORD");
    }

    @Test
    @DisplayName("주문 생성과 OrderCreated 적재는 같은 트랜잭션에서 끝난다")
    void createOrderRecordsEventInSameTransaction() {
        Order order = createOrder();

        List<OutboxEvent> published = outboxFor(order.getOrderNo());
        assertThat(published).hasSize(1);

        OutboxEvent event = published.get(0);
        assertThat(event.getEventType()).isEqualTo(EventType.ORDER_CREATED);
        assertThat(event.getAggregateType()).isEqualTo("Order");
        assertThat(event.getAggregateId()).isEqualTo(order.getOrderNo());
        // 파티션 키가 주문번호라는 것이 payment·license·settlement 의 순서 보장 전제다.
        // 여기가 흔들리면 같은 주문의 승인과 취소가 다른 파티션으로 갈라진다.
        assertThat(event.getPartitionKey()).isEqualTo(order.getOrderNo());
        // 항목이 실려야 payment 가 결제 라인을, settlement 가 판매자별 배분을 만들 수 있다
        assertThat(event.getPayload()).contains("로스트아크").contains("1001");
    }

    @Test
    @DisplayName("취소는 상태를 바꾸고 OrderCanceled 를 적재한다")
    void cancelOrderRecordsEvent() {
        Order order = createOrder();

        orderCommandService.cancelOrder(order.getOrderNo(), MEMBER, "USER_CANCEL");

        assertThat(statusOf(order.getOrderNo())).isEqualTo(OrderStatus.CANCELED);
        assertThat(outboxFor(order.getOrderNo()))
                .extracting(OutboxEvent::getEventType)
                .containsExactly(EventType.ORDER_CREATED, EventType.ORDER_CANCELED);
    }

    @Test
    @DisplayName("남의 주문은 취소할 수 없다")
    void cannotCancelSomeoneElsesOrder() {
        Order order = createOrder();

        assertThatThrownBy(() -> orderCommandService.cancelOrder(order.getOrderNo(), 999L, "USER_CANCEL"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);

        assertThat(statusOf(order.getOrderNo())).isEqualTo(OrderStatus.CREATED);
        // 소유권에서 막혔으면 이벤트도 나가지 않아야 한다
        assertThat(outboxFor(order.getOrderNo())).hasSize(1);
    }

    @Test
    @DisplayName("없는 주문을 취소하면 ORDER_NOT_FOUND")
    void cancelUnknownOrder() {
        assertThatThrownBy(() ->
                orderCommandService.cancelOrder("ORD-" + UUID.randomUUID(), MEMBER, "USER_CANCEL"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.ORDER_NOT_FOUND);
    }

    @Test
    @DisplayName("결제 완료 수신은 주문을 PAID 로 확정한다")
    void confirmPaid() {
        Order order = createOrder();

        orderCommandService.confirmPaid(UUID.randomUUID().toString(),
                EventType.PAYMENT_COMPLETED, order.getOrderNo());

        assertThat(statusOf(order.getOrderNo())).isEqualTo(OrderStatus.PAID);
    }

    @Test
    @DisplayName("같은 결제 완료 이벤트를 다시 받아도 마킹이 남아 재처리되지 않는다")
    void confirmPaidIsGuardedByInbox() {
        Order order = createOrder();
        String eventId = UUID.randomUUID().toString();

        orderCommandService.confirmPaid(eventId, EventType.PAYMENT_COMPLETED, order.getOrderNo());
        orderCommandService.confirmPaid(eventId, EventType.PAYMENT_COMPLETED, order.getOrderNo());

        assertThat(statusOf(order.getOrderNo())).isEqualTo(OrderStatus.PAID);
        assertThat(processedEventRepository.existsByEventIdAndConsumerGroup(eventId, "order")).isTrue();
    }

    @Test
    @DisplayName("결제 취소 수신은 주문을 CANCELED 로 반영한다")
    void confirmCanceled() {
        Order order = createOrder();
        orderCommandService.confirmPaid(UUID.randomUUID().toString(),
                EventType.PAYMENT_COMPLETED, order.getOrderNo());

        orderCommandService.confirmCanceled(UUID.randomUUID().toString(),
                EventType.PAYMENT_CANCELLED, order.getOrderNo(), "REFUND");

        assertThat(statusOf(order.getOrderNo())).isEqualTo(OrderStatus.CANCELED);
    }

    @Test
    @DisplayName("이미 취소된 주문에 취소가 또 와도 조용히 끝난다 — 컨슈머 경로다")
    void confirmCanceledIsIdempotentAcrossEvents() {
        Order order = createOrder();
        orderCommandService.confirmCanceled(UUID.randomUUID().toString(),
                EventType.PAYMENT_CANCELLED, order.getOrderNo(), "REFUND");

        // eventId 가 다르면 가드를 통과한다. 그 뒤를 막는 것은 Order.cancel 의 조기 반환이다.
        orderCommandService.confirmCanceled(UUID.randomUUID().toString(),
                EventType.PAYMENT_CANCELLED, order.getOrderNo(), "REFUND");

        assertThat(statusOf(order.getOrderNo())).isEqualTo(OrderStatus.CANCELED);
    }

    @Test
    @DisplayName("취소된 주문에 뒤늦은 결제 완료가 오면 확정하지 않는다")
    void latePaymentOnCanceledOrderIsRejected() {
        // PaymentCancelled 가 PaymentCompleted 를 추월한 순서 역전.
        Order order = createOrder();
        orderCommandService.confirmCanceled(UUID.randomUUID().toString(),
                EventType.PAYMENT_CANCELLED, order.getOrderNo(), "REFUND");

        assertThatThrownBy(() -> orderCommandService.confirmPaid(UUID.randomUUID().toString(),
                EventType.PAYMENT_COMPLETED, order.getOrderNo()))
                .isInstanceOf(BusinessException.class);

        assertThat(statusOf(order.getOrderNo())).isEqualTo(OrderStatus.CANCELED);
    }
}
