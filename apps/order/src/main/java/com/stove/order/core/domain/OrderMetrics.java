package com.stove.order.core.domain;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;

/**
 * 만료 스윕이 실제로 일하고 있는가.
 *
 * <p><b>왜 지표가 필요한가</b> — 스윕이 안 도는 상태는 정상 상태와 모습이 같다. 대상이 0건이면
 * "돌았는데 할 일이 없었다" 와 "아예 안 돌았다" 가 구분되지 않는다. 이 저장소가 D-021·D-023·D-031
 * 로 세 번 밟은 자리이므로 <b>장치를 넣을 때 그 장치가 살아 있다는 증거를 같이 넣는다.</b>
 *
 * <p>노출 지표
 * <ul>
 *   <li>{@code stove.order.expired} — 만료시킨 누적 건수. 스윕이 일하면 오른다</li>
 *   <li>{@code stove.order.pending} — 지금 {@code CREATED} 인 주문 수.
 *       <b>만료가 도입되기 전에는 이 값이 단조 증가만 했다</b></li>
 *   <li>{@code stove.order.expirable} — 그중 <b>이미 창을 넘긴</b> 건수.
 *       <b>알람은 이쪽에 건다.</b> 스윕이 멈추면 이 값이 계속 오르고, 정상이면 배치 크기 언저리에서
 *       머문다 — {@code pending} 은 정상 사용으로도 오르내리므로 그쪽에 걸면 잡음이 된다</li>
 * </ul>
 */
public class OrderMetrics {

    private final MeterRegistry registry;

    public OrderMetrics(MeterRegistry registry, OrderRepository repository, OrderProperties properties) {
        this.registry = registry;
        registry.gauge("stove.order.pending", repository,
                repo -> repo.countByStatus(OrderStatus.CREATED));
        // 기준 시각을 스크레이프 시점에 다시 계산해야 하므로 람다 안에서 now 를 읽는다 —
        // 밖에서 한 번 계산하면 그 값이 고정되어 시간이 흐르지 않는다.
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
