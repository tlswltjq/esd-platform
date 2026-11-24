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
