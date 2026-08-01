package com.stove.common.messaging.inbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 컨슈머 멱등 처리 기록(Inbox).
 * Kafka 재전송·리밸런싱으로 같은 이벤트가 여러 번 도착해도 부수효과는 한 번만 발생하게 한다.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "processed_event",
        uniqueConstraints = @UniqueConstraint(name = "uk_processed_event", columnNames = {"eventId", "consumerGroup"}))
public class ProcessedEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String eventId;

    @Column(nullable = false, length = 100)
    private String consumerGroup;

    @Column(nullable = false, length = 60)
    private String eventType;

    @Column(nullable = false)
    private Instant processedAt;

    private ProcessedEvent(String eventId, String consumerGroup, String eventType) {
        this.eventId = eventId;
        this.consumerGroup = consumerGroup;
        this.eventType = eventType;
        this.processedAt = Instant.now();
    }

    public static ProcessedEvent of(String eventId, String consumerGroup, String eventType) {
        return new ProcessedEvent(eventId, consumerGroup, eventType);
    }
}
