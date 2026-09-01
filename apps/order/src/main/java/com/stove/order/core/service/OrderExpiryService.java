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
 * 결제를 시작조차 하지 않은 주문을 닫는다. 정합성 문제가 아니라
 * <b>"미결제 주문 수" 가 지표로 못 쓰게 되는 것</b>이 이유다.
 *
 * <p><b>이벤트를 내지 않는다</b> — 하위 서비스 셋이 전부 "되돌릴 것이 없다" 로 끝나는 메시지를
 * 밀린 건수만큼 받게 된다. docs/code-notes.md
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderExpiryService {

    private final OrderRepository orderRepository;
    private final OrderProperties orderProperties;
    private final OrderMetrics orderMetrics;

    /**
     * 한 회차 분량을 만료시킨다. 배치 크기로 이미 묶여 있으므로 한 트랜잭션 안에서 끝낸다.
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
