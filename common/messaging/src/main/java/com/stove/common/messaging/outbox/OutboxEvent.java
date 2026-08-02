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
 * Transactional Outbox 레코드.
 * 비즈니스 데이터와 <b>같은 트랜잭션/같은 DB</b>에 저장되므로
 * "DB 는 커밋됐는데 Kafka 발행은 실패" 하는 이벤트 유실이 발생하지 않는다.
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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OutboxStatus status;

    @Column(nullable = false)
    private int retryCount;

    /**
     * 다음 발행 시도 시각. 실패할수록 뒤로 밀린다.
     *
     * <p>{@code null} 이면 즉시 대상이다(최초 적재, 또는 회수 직후).
     * 이 컬럼이 없던 시절에는 폴링 주기로 고정 재시도해서
     * 장애 감내 시간이 {@code max-retry x poll-interval} 로 못박혔다.
     */
    private Instant nextAttemptAt;

    @Column(length = 500)
    private String lastError;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant sentAt;

    private OutboxEvent(String eventId, String aggregateType, String aggregateId, String eventType,
                        String topic, String partitionKey, String payload) {
        this.eventId = eventId;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.topic = topic;
        this.partitionKey = partitionKey;
        this.payload = payload;
        this.status = OutboxStatus.PENDING;
        this.retryCount = 0;
        this.createdAt = Instant.now();
    }

    public static OutboxEvent pending(String eventId, String aggregateType, String aggregateId, String eventType,
                                      String topic, String partitionKey, String payload) {
        return new OutboxEvent(eventId, aggregateType, aggregateId, eventType, topic, partitionKey, payload);
    }

    public void markSent() {
        this.status = OutboxStatus.SENT;
        this.sentAt = Instant.now();
        this.lastError = null;
        this.nextAttemptAt = null;
    }

    /**
     * 발행 실패 기록. 다음 시도를 지수적으로 미뤄 브로커에 몰아치지 않게 한다.
     *
     * <p>한계를 넘기면 {@code DEAD} 로 두고 더 시도하지 않는다 —
     * 원인을 제거한 뒤 {@link #requeue()} 로 되살리는 것은 운영의 판단이다.
     */
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
     * DEAD 이벤트를 발행 대기로 되돌린다.
     *
     * <p>Outbox 의 존재 이유는 이벤트를 잃지 않는 것이다. 회수 경로가 없으면
     * 장애가 길어진 순간 유실 방지 장치가 유실의 원인이 된다.
     */
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
        /** 발행 대기 */
        PENDING,
        /** 브로커 ack 완료 */
        SENT,
        /** 재시도 한계 초과 → 운영 확인 대상 */
        DEAD
    }
}
