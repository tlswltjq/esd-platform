package com.stove.order.core.service;

import com.stove.order.core.domain.Order;
import com.stove.order.core.domain.OrderMetrics;
import com.stove.order.core.domain.OrderProperties;
import com.stove.order.core.domain.OrderRepository;
import com.stove.order.core.domain.OrderStatus;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결제를 시작조차 하지 않은 주문을 닫는다.
 *
 * <p><b>왜 필요한가</b> — {@code CREATED} 이후 상태는 전부 결제 결과 이벤트로 바뀐다.
 * 그래서 결제를 아예 시작하지 않은 주문은 아무 이벤트도 낳지 않아 영원히 {@code CREATED} 로 남는다.
 * 실측으로 전체 주문의 <b>96%</b>(98,750 / 102,950)가 그 상태였다.
 *
 * <p>정합성 문제는 아니다 — 금전 경로는 D-029 가 이미 닫았다(사전등록에 창을 걸었다).
 * 걸리는 것은 <b>"미결제 주문 수" 가 지표로 못 쓰게 되는 것</b>이다. 만료된 것과 진짜 결제를
 * 기다리는 것이 한 상태에 섞여 있으면 그 수를 세는 의미가 없다.
 *
 * <p><b>이벤트를 내지 않는다.</b> D-029 가 만료 스케줄러를 한 번 버렸던 이유는 payment 가
 * {@code OrderCanceled} 를 듣지 않아 결제 대기 레코드가 열린 채 남았기 때문이다. 지금은 그 이유가
 * 해소됐지만(payment 쪽 창이 닫혔다) <b>"아무도 반응하지 않는 이벤트를 내지 않는다" 는 판단은
 * 그대로다.</b> 여기서 이벤트를 내면 하위 서비스 셋이 전부 "되돌릴 것이 없다" 로 끝나는 메시지를
 * 밀린 건수만큼 받는다 — 실측 98,750건이면 그건 정리가 아니라 사고다.
 *
 * <p>대신 남는 것을 적어 둔다: <b>payment 의 {@code READY} 레코드는 그대로 쌓인다</b>(실측 51,984건).
 * 그쪽은 자기 창으로 이미 무해하지만 테이블은 계속 큰다. 같은 처방이 필요하면 그때는
 * payment 가 자기 시계로 자기 것을 닫는 편이 낫다 — 이벤트를 만드는 것보다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderExpiryService {

    private final OrderRepository orderRepository;
    private final OrderProperties orderProperties;
    private final OrderMetrics orderMetrics;

    /**
     * 한 회차 분량을 만료시킨다.
     *
     * <p>한 트랜잭션 안에서 끝낸다 — 배치 크기로 이미 묶여 있고, 여기서 한 건씩 트랜잭션을
     * 열면 밀린 것을 처리하는 동안 커밋이 배치 크기만큼 늘어난다.
     *
     * @return 이번 회차에 만료시킨 건수
     */
    @Transactional
    public int expireStaleOrders() {
        Instant threshold = Instant.now().minus(orderProperties.expireAfter());
        List<Order> stale = orderRepository.findByStatusAndCreatedAtBefore(
                OrderStatus.CREATED, threshold, PageRequest.of(0, orderProperties.expireBatchSize()));
        if (stale.isEmpty()) {
            return 0;
        }
        stale.forEach(Order::expire);
        orderMetrics.recordExpired(stale.size());
        log.info("미결제 주문 {}건을 만료시켰다 — 생성 후 {} 이상 결제가 시작되지 않았다",
                stale.size(), orderProperties.expireAfter());
        return stale.size();
    }
}
