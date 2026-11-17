package com.stove.common.messaging.direct;

import com.stove.common.event.EventHandler;
import com.stove.common.event.EventSubscriber;
import com.stove.common.event.InboundMessage;
import com.stove.common.event.KeyQuarantine;
import com.stove.common.kafka.EventHeaders;
import com.stove.common.messaging.trace.ReplayTracing;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 브로커 없음 기준선(결정 203)의 구독: 소비자가 발행 모듈의 {@code outbox_event} 를 직접 읽는다.
 *
 * <p>흐름: (소비 그룹, 토픽)마다 스레드 하나가 {@code pollIntervalMs} 마다 그 토픽의 {@code SENT} 행을
 * {@code (sent_at, id)} 순으로 읽는다 → 한 행씩 핸들러에 넘긴다 → 처리하면 그 위치를 소비 모듈의 {@code direct_cursor} 에 적는다.
 *
 * <p>순서(결정 202): 릴레이는 한 인스턴스이고(결정 205) 배치를 하나씩 커밋하므로 {@code (sent_at, id)} 는 커밋 순서대로 늘어난다.
 * 같은 키의 뒤 행은 앞 행이 {@code SENT} 가 되기 전에는 {@code SENT} 가 되지 않는다(PR #203). 그래서 이 순서로 한 줄로 읽으면
 * 같은 키는 발행 순서대로 한 번에 하나씩 처리된다.
 *
 * <p>실패: Kafka adapter 와 같이 제자리 재시도(1·2·4초, {@code noRetryFor} 는 생략) 뒤 {@link KeyQuarantine} 에 보관하고 키를 멈춘다.
 * 멈춘 키의 뒤 행은 처리하지 않고 쌓는다. 보관이 실패하면 위치를 넘기지 않고 다음 주기에 그 행부터 다시 읽는다.
 */
@Slf4j
public class DirectSubscriptions implements SmartLifecycle {

    static final List<Duration> RETRY_DELAYS = List.of(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4));
    private static final Pattern TABLE = Pattern.compile("^[a-z_]+(\\.[a-z_]+)?$");
    private static final Timestamp START = Timestamp.from(Instant.EPOCH);

    private final ApplicationContext beans;
    private final JdbcTemplate jdbc;
    private final KeyQuarantine quarantine;
    private final ReplayTracing tracing;
    private final Map<String, String> sources;
    private final long pollIntervalMs;
    private final int batchSize;
    private final List<Duration> retryDelays;
    private ScheduledExecutorService pollers;
    private volatile boolean running;

    public DirectSubscriptions(ApplicationContext beans, JdbcTemplate jdbc, KeyQuarantine quarantine, ReplayTracing tracing,
                               Map<String, String> sources, long pollIntervalMs, int batchSize) {
        this(beans, jdbc, quarantine, tracing, sources, pollIntervalMs, batchSize, RETRY_DELAYS);
    }

    DirectSubscriptions(ApplicationContext beans, JdbcTemplate jdbc, KeyQuarantine quarantine, ReplayTracing tracing,
                        Map<String, String> sources, long pollIntervalMs, int batchSize, List<Duration> retryDelays) {
        this.beans = beans;
        this.jdbc = jdbc;
        this.quarantine = quarantine;
        this.tracing = tracing;
        this.sources = sources;
        this.pollIntervalMs = pollIntervalMs;
        this.batchSize = batchSize;
        this.retryDelays = retryDelays;
    }

    /** 토픽을 발행하는 모듈의 Outbox 표. 설정이 없으면 {@code stove.<x>.v1 → stove_<x>.outbox_event}(release 는 catalog). */
    String sourceOf(String topic) {
        String table = sources.get(topic);
        if (table == null) {
            String module = topic.replaceFirst("^stove\\.", "").replaceFirst("\\.v\\d+$", "");
            table = "stove_" + ("release".equals(module) ? "catalog" : module) + ".outbox_event";
        }
        if (!TABLE.matcher(table).matches()) {
            throw new IllegalStateException("Outbox 표 이름 형식이 아니다: " + table);
        }
        return table;
    }

    @Override
    public void start() {
        Map<String, Subscription> subscriptions = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : beans.getBeansWithAnnotation(EventSubscriber.class).entrySet()) {
            if (!(entry.getValue() instanceof EventHandler handler)) {
                throw new IllegalStateException("@EventSubscriber 는 EventHandler 구현에만 단다: " + entry.getKey());
            }
            EventSubscriber subscription = beans.findAnnotationOnBean(entry.getKey(), EventSubscriber.class);
            for (String topic : subscription.topics()) {
                subscriptions.put(subscription.group() + "|" + topic,
                        new Subscription(subscription.group(), topic, sourceOf(topic), handler, subscription.noRetryFor()));
            }
        }
        pollers = Executors.newScheduledThreadPool(Math.max(1, subscriptions.size()));
        for (Subscription subscription : subscriptions.values()) {
            pollers.scheduleWithFixedDelay(() -> pollSafely(subscription), 0, pollIntervalMs, TimeUnit.MILLISECONDS);
        }
        running = true;
        log.info("브로커 없음 구독 {}개 시작 (주기 {}ms)", subscriptions.size(), pollIntervalMs);
    }

    @Override
    public void stop() {
        running = false;
        if (pollers != null) {
            pollers.shutdownNow();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void pollSafely(Subscription subscription) {
        try {
            poll(subscription);
        } catch (RuntimeException failure) {
            log.error("브로커 없음 폴링 실패, 다음 주기에 이어서 읽는다 group={} topic={}",
                    subscription.group(), subscription.topic(), failure);
        }
    }

    /** 한 주기. 처리한 행까지만 위치를 넘긴다. */
    void poll(Subscription subscription) {
        Cursor cursor = cursor(subscription);
        List<Row> rows = jdbc.query("SELECT id, event_id, event_type, partition_key, payload, created_at, published_at,"
                        + " trace_parent, sent_at FROM " + subscription.source()
                        + " WHERE topic = ? AND status = 'SENT' AND (sent_at > ? OR (sent_at = ? AND id > ?))"
                        + " ORDER BY sent_at, id LIMIT ?",
                (result, index) -> new Row(result.getLong("id"), result.getString("event_id"), result.getString("event_type"),
                        result.getString("partition_key"), result.getString("payload"),
                        result.getTimestamp("created_at").toInstant(), nullable(result.getTimestamp("published_at")),
                        result.getString("trace_parent"), result.getTimestamp("sent_at")),
                subscription.topic(), cursor.sentAt(), cursor.sentAt(), cursor.rowId(), batchSize);
        for (Row row : rows) {
            if (!running && pollers != null) {
                return;
            }
            InboundMessage message = row.toMessage(subscription.topic(), subscription.source());
            if (quarantine.stopped(subscription.group(), message.topic(), message.key())) {
                quarantine.quarantine(subscription.group(), message, null);
            } else {
                deliver(subscription, message);
            }
            jdbc.update("INSERT INTO direct_cursor (consumer_group, topic, sent_at, row_id) VALUES (?, ?, ?, ?)"
                            + " ON DUPLICATE KEY UPDATE sent_at = VALUES(sent_at), row_id = VALUES(row_id)",
                    subscription.group(), subscription.topic(), row.sentAt(), row.id());
        }
    }

    /** 제자리 재시도 뒤 격리. 격리 기록이 실패하면 예외가 나가 위치를 넘기지 않는다. */
    private void deliver(Subscription subscription, InboundMessage message) {
        for (int attempt = 0; ; attempt++) {
            try {
                tracing.runWithin(message.header(EventHeaders.TRACE_PARENT), () -> subscription.handler().receive(message));
                return;
            } catch (RuntimeException failure) {
                if (noRetry(subscription, failure) || attempt >= retryDelays.size()) {
                    quarantine.quarantine(subscription.group(), message, failure);
                    return;
                }
                sleep(retryDelays.get(attempt));
            }
        }
    }

    private static boolean noRetry(Subscription subscription, RuntimeException failure) {
        for (Class<? extends RuntimeException> type : subscription.noRetryFor()) {
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

    private Cursor cursor(Subscription subscription) {
        List<Cursor> found = jdbc.query("SELECT sent_at, row_id FROM direct_cursor WHERE consumer_group = ? AND topic = ?",
                (result, index) -> new Cursor(result.getTimestamp("sent_at"), result.getLong("row_id")),
                subscription.group(), subscription.topic());
        return found.isEmpty() ? new Cursor(START, 0L) : found.get(0);
    }

    private static Instant nullable(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    record Subscription(String group, String topic, String source, EventHandler handler,
                        Class<? extends RuntimeException>[] noRetryFor) {
    }

    private record Cursor(Timestamp sentAt, long rowId) {
    }

    private record Row(long id, String eventId, String eventType, String key, String payload, Instant createdAt,
                       Instant publishedAt, String traceParent, Timestamp sentAt) {

        /** 릴레이가 Kafka 로 싣는 것과 같은 헤더를 만든다(OutboxRelay#toMessage). */
        InboundMessage toMessage(String topic, String source) {
            Map<String, List<String>> headers = new LinkedHashMap<>();
            headers.put(EventHeaders.EVENT_ID, List.of(eventId));
            headers.put(EventHeaders.EVENT_TYPE, List.of(eventType));
            headers.put(EventHeaders.OCCURRED_AT, List.of(createdAt.toString()));
            if (publishedAt != null) {
                headers.put(EventHeaders.PUBLISHED_AT, List.of(publishedAt.toString()));
            }
            if (traceParent != null && !traceParent.isBlank()) {
                headers.put(EventHeaders.TRACE_PARENT, new ArrayList<>(List.of(traceParent)));
            }
            return new InboundMessage(topic, key, payload, headers, source + " id=" + id);
        }
    }
}
