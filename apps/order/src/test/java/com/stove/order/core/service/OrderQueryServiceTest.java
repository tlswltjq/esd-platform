package com.stove.order.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stove.common.core.error.BusinessException;
import com.stove.common.core.error.ErrorCode;
import com.stove.common.event.payload.OrderLine;
import com.stove.common.test.InfraContainers;
import com.stove.order.core.domain.Order;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * 주문 조회.
 *
 * <p>조회에도 소유권 검사가 걸려 있다는 것이 여기서 지킬 성질이다 —
 * 주문번호는 난수 10자리라 추측이 어렵지만, <b>추측 불가능성은 접근 통제가 아니다.</b>
 * 한 번 새어 나간 주문번호로 남의 주문 내역(구매 상품·금액)을 읽을 수 있으면 안 된다.
 */
@SpringBootTest(properties = "stove.outbox.relay-enabled=false")
@Import({InfraContainers.MySql.class, InfraContainers.Kafka.class})
class OrderQueryServiceTest {

    @Autowired
    OrderQueryService orderQueryService;
    @Autowired
    OrderCommandService orderCommandService;

    /** 테스트마다 다른 회원을 쓴다 — 같은 DB 를 공유하므로 목록 조회가 섞이지 않게. */
    private static Long uniqueMember() {
        return Math.abs(UUID.randomUUID().getLeastSignificantBits() % 1_000_000) + 100_000;
    }

    private Order orderFor(Long memberId, long unitPrice) {
        return orderCommandService.createOrder(memberId, "KRW",
                List.of(new OrderLine(1L, "로스트아크", 1001L, unitPrice, 1)));
    }

    @Test
    @DisplayName("주인은 자기 주문을 조회할 수 있다")
    void ownerReadsOwnOrder() {
        Long member = uniqueMember();
        Order created = orderFor(member, 39_000L);

        Order found = orderQueryService.getOrder(created.getOrderNo(), member);

        assertThat(found.getOrderNo()).isEqualTo(created.getOrderNo());
        // @EntityGraph 로 items 를 함께 읽는다 — 지연 로딩으로 바뀌면 응답 매핑에서 터진다
        assertThat(found.getItems()).hasSize(1);
        assertThat(found.getTotalAmount()).isEqualTo(39_000L);
    }

    @Test
    @DisplayName("남의 주문은 주문번호를 알아도 읽을 수 없다")
    void otherMemberCannotRead() {
        Order created = orderFor(uniqueMember(), 39_000L);

        assertThatThrownBy(() -> orderQueryService.getOrder(created.getOrderNo(), uniqueMember()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("없는 주문은 ORDER_NOT_FOUND")
    void unknownOrder() {
        assertThatThrownBy(() ->
                orderQueryService.getOrder("ORD-" + UUID.randomUUID(), uniqueMember()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.ORDER_NOT_FOUND);
    }

    @Test
    @DisplayName("내 주문 목록은 최신순이고 남의 주문을 포함하지 않는다")
    void myOrdersAreMineAndNewestFirst() {
        Long member = uniqueMember();
        Order first = orderFor(member, 10_000L);
        Order second = orderFor(member, 20_000L);
        orderFor(uniqueMember(), 30_000L);   // 다른 회원의 주문

        List<Order> orders = orderQueryService.getMyOrders(member);

        assertThat(orders)
                .extracting(Order::getOrderNo)
                .containsExactly(second.getOrderNo(), first.getOrderNo());
    }

    @Test
    @DisplayName("주문이 없는 회원은 빈 목록을 받는다 — 예외가 아니다")
    void emptyLibraryIsNotAnError() {
        assertThat(orderQueryService.getMyOrders(uniqueMember())).isEmpty();
    }
}
