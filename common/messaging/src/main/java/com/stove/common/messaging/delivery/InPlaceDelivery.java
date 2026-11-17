package com.stove.common.messaging.delivery;

import com.stove.common.event.EventHandler;
import com.stove.common.event.InboundMessage;
import com.stove.common.event.KeyQuarantine;
import com.stove.common.kafka.EventHeaders;
import com.stove.common.messaging.trace.ReplayTracing;
import java.time.Duration;
import java.util.List;

/**
 * Kafka 가 아닌 adapter(브로커 없음·RabbitMQ·Redis Streams)가 함께 쓰는 수신 처리 한 건.
 *
 * <p>흐름: 그 키가 멈춰 있으면 바로 보관한다 → 아니면 원래 traceparent 안에서 핸들러를 부른다 →
 * 실패하면 제자리에서 {@code retryDelays} 만큼 쉬며 다시 부른다({@code noRetryFor} 는 첫 실패에서 바로) →
 * 그래도 실패하면 {@link KeyQuarantine} 에 보관하고 키를 멈춘다. Kafka adapter 의 1·2·4초 재시도와 같다(결정 202·206).
 *
 * <p>보관 기록이 실패하면 예외가 나간다. 호출한 adapter 는 그 메시지를 처리하지 않은 것으로 두고(위치·ack 를 넘기지 않음) 다시 받는다.
 */
public class InPlaceDelivery {

    public static final List<Duration> RETRY_DELAYS = List.of(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4));

    private final KeyQuarantine quarantine;
    private final ReplayTracing tracing;
    private final List<Duration> retryDelays;

    public InPlaceDelivery(KeyQuarantine quarantine, ReplayTracing tracing, List<Duration> retryDelays) {
        this.quarantine = quarantine;
        this.tracing = tracing;
        this.retryDelays = List.copyOf(retryDelays);
    }

    public void deliver(String group, EventHandler handler, Class<? extends RuntimeException>[] noRetryFor,
                        InboundMessage message) {
        if (quarantine.stopped(group, message.topic(), message.key())) {
            quarantine.quarantine(group, message, null);
            return;
        }
        for (int attempt = 0; ; attempt++) {
            try {
                tracing.runWithin(message.header(EventHeaders.TRACE_PARENT), () -> handler.receive(message));
                return;
            } catch (RuntimeException failure) {
                if (noRetry(noRetryFor, failure) || attempt >= retryDelays.size()) {
                    quarantine.quarantine(group, message, failure);
                    return;
                }
                sleep(retryDelays.get(attempt));
            }
        }
    }

    private static boolean noRetry(Class<? extends RuntimeException>[] noRetryFor, RuntimeException failure) {
        for (Class<? extends RuntimeException> type : noRetryFor) {
            Throwable cause = failure;
            while (cause != null) {
                if (type.isInstance(cause)) {
                    return true;
                }
                cause = cause.getCause();
            }
        }
        return false;
    }

    private static void sleep(Duration delay) {
        try {
            Thread.sleep(delay.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("재시도 대기 중 멈춤", interrupted);
        }
    }
}
