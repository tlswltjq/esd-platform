package com.stove.common.messaging.delivery;

import com.stove.common.event.EventHandler;
import com.stove.common.event.InboundMessage;
import com.stove.common.event.KeyQuarantine;
import com.stove.common.contract.EventHeaders;
import com.stove.common.messaging.trace.ReplayTracing;
import java.time.Duration;
import java.util.List;
import java.util.function.BooleanSupplier;
import lombok.extern.slf4j.Slf4j;

/**
 * Kafka 가 아닌 adapter(브로커 없음·RabbitMQ·Redis Streams)가 함께 쓰는 수신 처리 한 건.
 *
 * <p>흐름: 그 키가 멈춰 있으면 바로 보관한다 → 아니면 원래 traceparent 안에서 핸들러를 부른다 →
 * 실패하면 제자리에서 {@code retryDelays} 만큼 쉬며 다시 부른다({@code noRetryFor} 는 첫 실패에서 바로) →
 * 그래도 실패하면 {@link KeyQuarantine} 에 보관하고 키를 멈춘다. Kafka adapter 의 1·2·4초 재시도와 같다(결정 202·206).
 *
 * <p>보관 기록이 실패하면 예외가 나간다. 호출한 adapter 는 그 메시지를 처리하지 않은 것으로 두고(위치·ack 를 넘기지 않음) 다시 받는다.
 */
@Slf4j
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
        deliver(group, handler, noRetryFor, message, () -> true);
    }

    /**
     * @param stillMine 재시도·격리 보관 직전마다 묻는다. 거짓이면(멈추는 중이거나 점유를 잃음) 그 자리에서 멈추고
     *                  {@link IllegalStateException} 을 던진다 — 처리를 넘겨받은 쪽과 같은 메시지를 겹쳐 처리하지 않게(D-058).
     */
    public void deliver(String group, EventHandler handler, Class<? extends RuntimeException>[] noRetryFor,
                        InboundMessage message, BooleanSupplier stillMine) {
        if (quarantine.stopped(group, message.topic(), message.key())) {
            requireStillMine(stillMine, message);
            quarantine.quarantine(group, message, null);
            return;
        }
        for (int attempt = 0; ; attempt++) {
            if (attempt > 0) {
                requireStillMine(stillMine, message);
            }
            try {
                tracing.runWithin(message.header(EventHeaders.TRACE_PARENT), () -> handler.receive(message));
                return;
            } catch (RuntimeException failure) {
                if (noRetry(noRetryFor, failure) || attempt >= retryDelays.size()) {
                    requireStillMine(stillMine, message);
                    quarantine.quarantine(group, message, failure);
                    return;
                }
                sleep(retryDelays.get(attempt));
            }
        }
    }

    private static void requireStillMine(BooleanSupplier stillMine, InboundMessage message) {
        if (!stillMine.getAsBoolean()) {
            throw new IllegalStateException("멈추는 중이거나 점유를 잃어 재시도·보관을 멈춘다 origin=" + message.origin());
        }
    }

    /** 보관 실패 때 처음 쉬는 시간. 실패가 이어지면 두 배씩 늘려 {@link #HOLD_MAX} 까지. */
    public static final Duration HOLD_START = Duration.ofSeconds(1);
    public static final Duration HOLD_MAX = Duration.ofSeconds(30);

    /**
     * {@link #deliver} 가 끝날 때까지(처리 또는 격리 보관) 이 메시지를 붙잡는다. 보관(또는 정지 확인)이 실패하면
     * {@code holdStart} 부터 두 배씩(최대 {@link #HOLD_MAX}) 쉬었다 다시 한다. 브로커로 되돌리면 이미 받아 둔 같은 키의
     * 뒤 메시지가 먼저 처리되기 때문이다(PR #211 R1). {@code running} 이 거짓(멈추는 중)이면 예외를 그대로 내보낸다.
     */
    public void deliverHolding(String group, EventHandler handler, Class<? extends RuntimeException>[] noRetryFor,
                               InboundMessage message, BooleanSupplier running, Duration holdStart) {
        Duration wait = holdStart;
        while (true) {
            try {
                deliver(group, handler, noRetryFor, message, running);
                return;
            } catch (RuntimeException failure) {
                if (!running.getAsBoolean()) {
                    throw failure;
                }
                log.error("격리 보관 실패, 이 메시지를 붙잡고 {}ms 뒤 다시 처리한다 group={} topic={} key={} origin={}",
                        wait.toMillis(), group, message.topic(), message.key(), message.origin(), failure);
                try {
                    Thread.sleep(wait.toMillis());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw failure;
                }
                // 기다리는 사이 멈추거나 점유를 잃었으면 다시 들어가지 않는다(PR #215 3차 확인 R1).
                if (!running.getAsBoolean()) {
                    throw failure;
                }
                wait = wait.multipliedBy(2).compareTo(HOLD_MAX) > 0 ? HOLD_MAX : wait.multipliedBy(2);
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
