package com.stove.common.kafka;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * DLT 로 넘어간 메시지 수.
 *
 * <p><b>알람을 걸 자리가 필요해서 둔다.</b> DLT 는 유실을 막지만 <i>알려주지는</i> 않는다 —
 * 아무도 보지 않으면 메시지가 조용히 쌓이기만 하고, 그건 유실과 운영상 크게 다르지 않다.
 * "포기했다"가 사람에게 도달해야 비로소 복구가 시작된다.
 *
 * <p>토픽을 태그로 붙인다. 카디널리티는 토픽 수(고정)만큼이고,
 * <b>어느 흐름이 막혔는지</b>가 알람의 첫 질문이라 이 태그가 없으면 결국 로그를 뒤져야 한다.
 *
 * <p>노출 지표: {@code stove.kafka.dead-lettered} (Prometheus 에서는 {@code stove_kafka_dead_lettered_total})
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
