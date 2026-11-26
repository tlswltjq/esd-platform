package com.stove.common.messaging.kafka;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.listener.RetryListener;
import org.springframework.kafka.support.KafkaUtils;

/**
 * 소비 재시도·격리를 센다({@code DefaultErrorHandler} 의 {@link RetryListener}). 처리 동작은 바꾸지 않는다(결정 229).
 *
 * <ul>
 *   <li>{@code stove.kafka.consumer.failed-deliveries} — 리스너가 레코드 처리에 실패한 횟수(최초 처리 실패 포함, 재시도마다 1).
 *       {@code exception} 태그는 맨 안쪽 원인의 클래스 이름이다.</li>
 *   <li>{@code stove.kafka.consumer.recovered} — 재시도를 다 썼거나 재시도 제외 예외라 격리처(소비 모듈 DB 격리 표 또는 DLT)로
 *       넘긴 레코드 수.</li>
 *   <li>{@code stove.kafka.consumer.recovery-failures} — 격리처 기록이 실패한 횟수. 그 레코드는 offset 을 넘기지 않고 다시 받는다.</li>
 * </ul>
 *
 * <p>태그는 토픽·소비 그룹(소비자 스레드에서 읽음, 없으면 {@code unknown})이다. 메시지별 재시도 성공 여부는 이 지표로 셀 수 없다 —
 * 그것은 층 2 벤치가 메시지 ID 로 센다.
 */
public class ConsumerRetryMetrics implements RetryListener {

    static final String FAILED_DELIVERIES = "stove.kafka.consumer.failed-deliveries";
    static final String RECOVERED = "stove.kafka.consumer.recovered";
    static final String RECOVERY_FAILURES = "stove.kafka.consumer.recovery-failures";
    private static final String UNKNOWN = "unknown";

    private final MeterRegistry registry;

    public ConsumerRetryMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void failedDelivery(ConsumerRecord<?, ?> record, Exception ex, int deliveryAttempt) {
        Counter.builder(FAILED_DELIVERIES)
                .description("리스너 처리 실패 횟수(재시도마다 1)")
                .tag("topic", record.topic())
                .tag("group", group())
                .tag("exception", rootCause(ex).getClass().getSimpleName())
                .register(registry)
                .increment();
    }

    @Override
    public void recovered(ConsumerRecord<?, ?> record, Exception ex) {
        Counter.builder(RECOVERED)
                .description("재시도를 다 써 격리처(격리 표·DLT)로 넘긴 레코드 수")
                .tag("topic", record.topic())
                .tag("group", group())
                .register(registry)
                .increment();
    }

    @Override
    public void recoveryFailed(ConsumerRecord<?, ?> record, Exception original, Exception failure) {
        Counter.builder(RECOVERY_FAILURES)
                .description("격리처 기록 실패 횟수 — 원본은 offset 을 넘기지 않고 다시 받는다")
                .tag("topic", record.topic())
                .tag("group", group())
                .register(registry)
                .increment();
    }

    private static String group() {
        String group = KafkaUtils.getConsumerGroupId();
        return group == null || group.isBlank() ? UNKNOWN : group;
    }

    private static Throwable rootCause(Throwable thrown) {
        Throwable cause = thrown;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }
}
