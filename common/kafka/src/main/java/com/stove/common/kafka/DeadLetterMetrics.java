package com.stove.common.kafka;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * DLT 로 넘어간 메시지 수({@code stove.kafka.dead-lettered}).
 * <b>DLT 는 유실을 막지만 알려주지는 않는다</b> — 알람을 걸 자리다. docs/code-notes.md
 */
public class DeadLetterMetrics {

    private final MeterRegistry registry;

    public DeadLetterMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordDeadLettered(String originalTopic) {
        Counter.builder("stove.kafka.dead-lettered")
                .description("재시도를 소진해 DLT 로 넘긴 메시지 수 — 운영 확인 대상")
                .tag("topic", originalTopic)
                .register(registry)
                .increment();
    }
}
