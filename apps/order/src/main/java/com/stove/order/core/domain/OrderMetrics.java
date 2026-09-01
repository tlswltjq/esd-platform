package com.stove.order.core.domain;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;

/**
 * 만료 스윕이 실제로 일하고 있는가. <b>스윕이 안 도는 상태는 정상 상태와 모습이 같다</b> —
 * D-021·D-023·D-031 로 세 번 밟은 자리다. 알람은 {@code expirable} 에 건다. docs/code-notes.md
 */
public class OrderMetrics {

    private final MeterRegistry registry;

    public OrderMetrics(MeterRegistry registry, OrderRepository repository, OrderProperties properties) {
        this.registry = registry;
        registry.gauge("stove.order.pending", repository,
                repo -> repo.countByStatus(OrderStatus.CREATED));
        // now 는 반드시 람다 안에서 읽는다 — 밖에서 계산하면 시간이 흐르지 않는다.
        registry.gauge("stove.order.expirable", repository,
                repo -> repo.countByStatusAndCreatedAtBefore(
                        OrderStatus.CREATED, Instant.now().minus(properties.expireAfter())));
    }

    /** @param count 이번 회차에 만료시킨 건수. 회차마다 부르지 않고 건수만큼 올린다. */
    public void recordExpired(int count) {
        Counter.builder("stove.order.expired")
                .description("결제를 시작하지 않아 만료된 주문 수")
                .register(registry)
                .increment(count);
    }
}
