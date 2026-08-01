package com.stove.common.messaging.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
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
    }

    public void markFailed(String error, int maxRetry) {
        this.retryCount++;
        this.lastError = error != null && error.length() > 500 ? error.substring(0, 500) : error;
        if (this.retryCount >= maxRetry) {
            this.status = OutboxStatus.DEAD;
        }
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
