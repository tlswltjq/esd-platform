package com.stove.common.messaging.direct;

import com.stove.common.event.EventHandler;
import com.stove.common.event.InboundMessage;
import com.stove.common.event.KeyQuarantine;
import com.stove.common.contract.EventHeaders;
import com.stove.common.messaging.delivery.InPlaceDelivery;
import com.stove.common.messaging.delivery.SubscribedHandler;
import com.stove.common.messaging.trace.ReplayTracing;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
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
 *
 * <p>인스턴스가 여럿이면: (그룹, 토픽)마다 {@code direct_cursor} 한 줄을 한 인스턴스만 점유해 읽는다(기한 {@link #LEASE}, DB 시각).
 * 그래서 소비 인스턴스를 늘려도 한 토픽은 한 곳에서만 처리된다 — Kafka 의 파티션 같은 나눔이 없는 이 기준선의 한계다.
 */
@Slf4j
public class DirectSubscriptions implements SmartLifecycle {

    private static final Pattern TABLE = Pattern.compile("^[a-z_]+(\\.[a-z_]+)?$");
    private static final Timestamp START = Timestamp.from(Instant.parse("2000-01-01T00:00:00Z"));
    /**
     * 점유 기한. 한 행의 재시도(1+2+4초)와 처리보다 넉넉해야 한다. 처리가 이보다 길어 다른 인스턴스가 이어받아도
     * 업무는 Inbox 로 한 번만 반영되고, 늦은 쪽은 펜싱으로 위치를 되돌리지 못한다.
     */
    static final Duration LEASE = Duration.ofSeconds(60);

    private final Supplier<List<SubscribedHandler>> handlers;
    private final JdbcTemplate jdbc;
    private final InPlaceDelivery delivery;
    private final Map<String, String> sources;
    private final long pollIntervalMs;
    private final int batchSize;
    private final String instanceId = UUID.randomUUID().toString();
    private ScheduledExecutorService pollers;
    private volatile boolean running;

    /** @param handlers 구독할 (그룹, 토픽, 핸들러). RabbitMQ·Redis Streams adapter 와 같은 모양이다(결정 209). */
    public DirectSubscriptions(Supplier<List<SubscribedHandler>> handlers, JdbcTemplate jdbc, KeyQuarantine quarantine,
                               ReplayTracing tracing, Map<String, String> sources, long pollIntervalMs, int batchSize) {
        this(handlers, jdbc, quarantine, tracing, sources, pollIntervalMs, batchSize, InPlaceDelivery.RETRY_DELAYS);
    }

    DirectSubscriptions(Supplier<List<SubscribedHandler>> handlers, JdbcTemplate jdbc, KeyQuarantine quarantine,
                        ReplayTracing tracing, Map<String, String> sources, long pollIntervalMs, int batchSize,
                        List<Duration> retryDelays) {
        this.handlers = handlers;
        this.jdbc = jdbc;
        this.delivery = new InPlaceDelivery(quarantine, tracing, retryDelays);
        this.sources = sources;
        this.pollIntervalMs = pollIntervalMs;
        this.batchSize = batchSize;
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
        for (SubscribedHandler found : handlers.get()) {
            subscriptions.put(found.group() + "|" + found.topic(), new Subscription(found.group(), found.topic(),
                    sourceOf(found.topic()), found.handler(), found.noRetryFor()));
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
            try {
                pollers.awaitTermination(LEASE.toSeconds(), TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        releaseAll();
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

    /**
     * 한 주기. 그 (그룹, 토픽)의 점유를 얻은 인스턴스만 읽고, 처리한 행까지만 위치를 넘긴다.
     * 위치를 넘길 때 점유가 아직 내 것인지 확인한다(펜싱) — 점유를 잃었으면 위치를 건드리지 않고 멈춘다(PR #210 R1).
     */
    void poll(Subscription subscription) {
        if (!claim(subscription)) {
            return;
        }
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
            delivery.deliver(subscription.group(), subscription.handler(), subscription.noRetryFor(), message);
            int advanced = jdbc.update("UPDATE direct_cursor SET sent_at = ?, row_id = ?,"
                            + " lease_until = NOW(6) + INTERVAL ? SECOND"
                            + " WHERE consumer_group = ? AND topic = ? AND owner = ?",
                    row.sentAt(), row.id(), LEASE.toSeconds(), subscription.group(), subscription.topic(), instanceId);
            if (advanced == 0) {
                log.warn("점유를 잃어 위치를 넘기지 않고 멈춘다 group={} topic={} row={}",
                        subscription.group(), subscription.topic(), row.id());
                return;
            }
        }
    }

    /**
     * (그룹, 토픽) 한 줄을 이 인스턴스가 점유한다. 비어 있거나, 이미 내 것이거나, 기한이 지났으면 가져온다.
     * 기한은 DB 시각({@code NOW(6)})으로 재므로 인스턴스끼리 시계가 달라도 된다.
     */
    private boolean claim(Subscription subscription) {
        jdbc.update("INSERT IGNORE INTO direct_cursor (consumer_group, topic, sent_at, row_id) VALUES (?, ?, ?, 0)",
                subscription.group(), subscription.topic(), START);
        return jdbc.update("UPDATE direct_cursor SET owner = ?, lease_until = NOW(6) + INTERVAL ? SECOND"
                        + " WHERE consumer_group = ? AND topic = ?"
                        + " AND (owner IS NULL OR owner = ? OR lease_until < NOW(6))",
                instanceId, LEASE.toSeconds(), subscription.group(), subscription.topic(), instanceId) == 1;
    }

    /** 멈출 때 점유를 놓아 다른 인스턴스가 기한을 기다리지 않고 이어받게 한다. */
    private void releaseAll() {
        try {
            jdbc.update("UPDATE direct_cursor SET owner = NULL, lease_until = NULL WHERE owner = ?", instanceId);
        } catch (RuntimeException failure) {
            log.warn("점유 해제 실패, 기한이 지나면 다른 인스턴스가 이어받는다", failure);
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
