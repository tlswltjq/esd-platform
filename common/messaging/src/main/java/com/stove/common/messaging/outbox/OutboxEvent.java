package com.stove.common.messaging.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Transactional Outbox 레코드. 비즈니스 데이터와 <b>같은 트랜잭션/같은 DB</b> 에 저장된다.
 * docs/code-notes.md
 */
@Entity
@Getter
@Table(name = "outbox_event")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxEvent {

    private static final Duration BASE_RETRY_DELAY = Duration.ofSeconds(1);
    private static final Duration MAX_RETRY_DELAY = Duration.ofMinutes(5);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 이벤트 고유 ID. 컨슈머 측 멱등 처리 키로 그대로 전달된다. */
    @Column(nullable = false, unique = true, length = 64)
    private String eventId;

    @Column(nullable = false, length = 50)
    private String aggregateType;

    @Column(nullable = false, length = 100)
    private String aggregateId;

    @Column(nullable = false, length = 60)
    private String eventType;

    @Column(nullable = false, length = 100)
    private String topic;

    @Column(nullable = false, length = 100)
    private String partitionKey;

    @Column(nullable = false, columnDefinition = "json")
    private String payload;

    /**
     * 적재 시점의 W3C traceparent. 추적이 꺼져 있으면 {@code null}.
     * <b>재시도해도, 회수해도 바뀌지 않는다.</b> 왜 이 표에 있는지는 docs/code-notes.md
     */
    @Column(length = 64)
    private String traceParent;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OutboxStatus status;

    @Column(nullable = false)
    private int retryCount;

    /** 다음 발행 시도 시각. {@code null} 이면 즉시 대상(최초 적재 또는 회수 직후). */
    private Instant nextAttemptAt;

    @Column(length = 500)
    private String lastError;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant sentAt;

    private OutboxEvent(String eventId, String aggregateType, String aggregateId, String eventType,
                        String topic, String partitionKey, String payload, String traceParent) {
        this.eventId = eventId;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.topic = topic;
        this.partitionKey = partitionKey;
        this.payload = payload;
        this.traceParent = traceParent;
        this.status = OutboxStatus.PENDING;
        this.retryCount = 0;
        this.createdAt = Instant.now();
    }

    /** 추적 컨텍스트 없이 적재한다. 추적을 구성하지 않은 서비스와 테스트의 진입점이다. */
    public static OutboxEvent pending(String eventId, String aggregateType, String aggregateId, String eventType,
                                      String topic, String partitionKey, String payload) {
        return pending(eventId, aggregateType, aggregateId, eventType, topic, partitionKey, payload, null);
    }

    /**
     * 적재 시점의 추적 컨텍스트를 함께 남긴다.
     *
     * @param traceParent 요청 스레드에서 붙잡은 W3C traceparent, 추적이 없으면 {@code null}
     */
    public static OutboxEvent pending(String eventId, String aggregateType, String aggregateId, String eventType,
                                      String topic, String partitionKey, String payload, String traceParent) {
        return new OutboxEvent(eventId, aggregateType, aggregateId, eventType, topic, partitionKey,
                payload, traceParent);
    }

    public void markSent() {
        this.status = OutboxStatus.SENT;
        this.sentAt = Instant.now();
        this.lastError = null;
        this.nextAttemptAt = null;
    }

    /** 발행 실패 기록. 한계를 넘기면 {@code DEAD} — 되살리는 것은 운영의 판단이다. */
    public void markFailed(String error, int maxRetry) {
        this.retryCount++;
        this.lastError = error != null && error.length() > 500 ? error.substring(0, 500) : error;
        if (this.retryCount >= maxRetry) {
            this.status = OutboxStatus.DEAD;
            this.nextAttemptAt = null;
            return;
        }
        this.nextAttemptAt = Instant.now().plus(backOffDelay(this.retryCount));
    }

    /**
     * 같은 키의 앞 이벤트가 재시도 대기에 들어가서 함께 보류된다. [D-014]
     * <b>{@code retryCount} 를 늘리지 않는다</b> — 실패한 것이 아니라 양보한 것이다.
     * 앞이 DEAD 면({@code until} 이 null) 보류하지 않는다 — 그 키가 영구 정지한다.
     * docs/code-notes.md
     */
    public void holdUntil(Instant until) {
        if (this.status != OutboxStatus.PENDING || until == null) {
            return;
        }
        // 이미 더 뒤면 그대로 둔다 — 앞 이벤트보다 먼저 나가지만 않으면 된다.
        if (this.nextAttemptAt == null || this.nextAttemptAt.isBefore(until)) {
            this.nextAttemptAt = until;
        }
    }

    /** DEAD 를 발행 대기로 되돌린다. <b>회수 경로가 없으면 유실 방지 장치가 유실의 원인이 된다.</b> */
    public void requeue() {
        if (this.status != OutboxStatus.DEAD) {
            return;
        }
        this.status = OutboxStatus.PENDING;
        this.retryCount = 0;
        this.nextAttemptAt = null;
        this.lastError = null;
    }

    /** 1초에서 시작해 두 배씩, 상한까지. 시프트가 넘치지 않도록 지수를 먼저 자른다. */
    static Duration backOffDelay(int retryCount) {
        int exponent = Math.min(Math.max(retryCount - 1, 0), 30);
        long seconds = BASE_RETRY_DELAY.toSeconds() << exponent;
        return seconds >= MAX_RETRY_DELAY.toSeconds() ? MAX_RETRY_DELAY : Duration.ofSeconds(seconds);
    }

    public enum OutboxStatus {
        PENDING,
        /** 브로커 ack 완료 */
        SENT,
        /** 재시도 한계 초과 → 운영 확인 대상 */
        DEAD
    }
}
