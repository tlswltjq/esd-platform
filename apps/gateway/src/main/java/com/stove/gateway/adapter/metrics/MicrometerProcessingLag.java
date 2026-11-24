package com.stove.gateway.adapter.metrics;

import com.stove.gateway.core.port.ProcessingLag;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@code stove.account.access.consumer.lag}(초): 처리하지 못하고 있는 계정 접근 변경 사실의 나이(R5.8). 소비가 멈추면(Redis
 * 장애로 성공까지 재시도 중, 결정 226) 계속 늘어난다. 밀린 것이 없으면 0. 경보 기준은 측정 뒤 정한다(결정 159).
 *
 * <p>이 앱이 가져와 붙잡고 있는 사실만 잰다. 브로커에 닿지 못하거나 소비자가 멈춰 아예 가져오지 못한 적체는 브로커 쪽 그룹 지연
 * ({@code kafka_consumergroup_lag_sum}, kafka-exporter)으로 재고, 기존 경보 {@code ConsumerStalled}·{@code ConsumerLagGrowing}이
 * 그룹을 가리지 않고 이 소비 그룹에도 걸린다(PR #246 R3).
 */
public class MicrometerProcessingLag implements ProcessingLag {

    public static final String METRIC = "stove.account.access.consumer.lag";

    private final Clock clock;
    private final AtomicReference<Instant> pending = new AtomicReference<>();

    public MicrometerProcessingLag(MeterRegistry registry, String consumerGroup, Clock clock) {
        this.clock = clock;
        Gauge.builder(METRIC, this, MicrometerProcessingLag::seconds)
                .tag("group", consumerGroup).baseUnit("seconds")
                .description("처리하지 못하고 있는 계정 접근 변경 사실의 나이").register(registry);
    }

    @Override
    public void started(Instant occurredAt) {
        pending.compareAndSet(null, occurredAt);
    }

    @Override
    public void finished() {
        pending.set(null);
    }

    double seconds() {
        Instant oldest = pending.get();
        return oldest == null ? 0 : Math.max(0, clock.millis() - oldest.toEpochMilli()) / 1000.0;
    }
}
