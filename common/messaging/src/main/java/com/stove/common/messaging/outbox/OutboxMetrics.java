package com.stove.common.messaging.outbox;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.TimeUnit;

/**
 * 릴레이 계측. 배경 스레드에서 도는 작업이라 <b>지표가 없으면 관측할 방법이 아예 없다</b> —
 * 로그는 재현성이 없고, DB 를 직접 폴링하면 측정이 측정 대상에 부하를 준다.
 *
 * <p>부하 테스트용 임시물이 아니라 운영에 필요한 코드다. 이벤트가 밀리고 있는지,
 * DEAD 로 떨어지고 있는지는 사고가 난 뒤가 아니라 나기 전에 보여야 한다.
 *
 * <p>노출 지표
 * <ul>
 *   <li>{@code stove.outbox.published} — 발행 성공 건수</li>
 *   <li>{@code stove.outbox.failed} — 발행 실패 건수(재시도 대상)</li>
 *   <li>{@code stove.outbox.dead} — 재시도 소진으로 포기한 건수. <b>알람 대상</b></li>
 *   <li>{@code stove.outbox.relay} — 릴레이 1회 소요시간</li>
 *   <li>{@code stove.outbox.batch.size} — 회차당 처리 건수</li>
 *   <li>{@code stove.outbox.pending} — 발행 대기 적체량</li>
 * </ul>
 */
public class OutboxMetrics {

    private final Counter published;
    private final Counter failed;
    private final Counter dead;
    private final Timer relayDuration;
    private final io.micrometer.core.instrument.DistributionSummary batchSize;

    public OutboxMetrics(MeterRegistry registry, OutboxEventRepository repository) {
        this.published = Counter.builder("stove.outbox.published")
                .description("Kafka 로 발행에 성공한 이벤트 수")
                .register(registry);
        this.failed = Counter.builder("stove.outbox.failed")
                .description("발행에 실패해 재시도 대기로 돌아간 이벤트 수")
                .register(registry);
        this.dead = Counter.builder("stove.outbox.dead")
                .description("재시도를 소진해 DEAD 로 전이한 이벤트 수 — 운영 확인 대상")
                .register(registry);
        this.relayDuration = Timer.builder("stove.outbox.relay")
                .description("릴레이 1회 소요시간")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
        this.batchSize = io.micrometer.core.instrument.DistributionSummary
                .builder("stove.outbox.batch.size")
                .description("릴레이 회차당 처리 건수")
                .register(registry);

        // 적체량. 스크레이프 시점에 세므로 (status, next_attempt_at, id) 인덱스를 그대로 탄다.
        registry.gauge("stove.outbox.pending", repository,
                repo -> repo.countByStatus(OutboxEvent.OutboxStatus.PENDING));
    }

    public void recordPublished() {
        published.increment();
    }

    /** 실패는 재시도 대상과 포기(DEAD)를 나눠 센다 — 둘의 운영 의미가 다르다. */
    public void recordFailed(OutboxEvent event) {
        failed.increment();
        if (event.getStatus() == OutboxEvent.OutboxStatus.DEAD) {
            dead.increment();
        }
    }

    public void recordRelay(long elapsedNanos, int processed) {
        relayDuration.record(elapsedNanos, TimeUnit.NANOSECONDS);
        batchSize.record(processed);
    }
}
