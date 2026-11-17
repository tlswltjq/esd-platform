package com.stove.common.messaging.quarantine;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 소비하지 못한 메시지 한 건과 그 키의 정지(결정 160: 보관과 정지는 같은 표·같은 트랜잭션).
 * 원래 순서는 {@code id} 순이다. 브로커가 무엇이든 같은 모양으로 남는다(결정 206).
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "consumer_quarantine")
public class QuarantinedMessage {

    static final int FAILURE_LENGTH = 1000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String consumerGroup;

    @Column(nullable = false, length = 100)
    private String topic;

    /** 순서 키. 키가 없는 메시지는 빈 문자열로 둔다(그런 메시지끼리 한 줄로 멈춘다). */
    @Column(nullable = false, length = 100)
    private String partitionKey;

    @Column(length = 64)
    private String eventId;

    @Column(length = 60)
    private String eventType;

    @Column(columnDefinition = "MEDIUMTEXT")
    private String payload;

    /** 받은 헤더 전부(JSON, 이름 → 값 목록). 다시 넣을 때 같은 메시지를 만든다. */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String headers;

    @Column(nullable = false, length = 200)
    private String origin;

    @Column(nullable = false, length = FAILURE_LENGTH)
    private String failure;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private QuarantineStatus status;

    @Column(nullable = false)
    private Instant quarantinedAt;

    private Instant replayedAt;

    static QuarantinedMessage held(String consumerGroup, String topic, String partitionKey, String eventId,
                                   String eventType, String payload, String headers, String origin, String failure) {
        QuarantinedMessage message = new QuarantinedMessage();
        message.consumerGroup = consumerGroup;
        message.topic = topic;
        message.partitionKey = partitionKey;
        message.eventId = eventId;
        message.eventType = eventType;
        message.payload = payload;
        message.headers = headers;
        message.origin = origin.length() > 200 ? origin.substring(0, 200) : origin;
        message.failure = failure.length() > FAILURE_LENGTH ? failure.substring(0, FAILURE_LENGTH) : failure;
        message.status = QuarantineStatus.HELD;
        message.quarantinedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        return message;
    }

    void markReplayed() {
        this.status = QuarantineStatus.REPLAYED;
        this.replayedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    void recordReplayFailure(String reason) {
        this.failure = reason.length() > FAILURE_LENGTH ? reason.substring(0, FAILURE_LENGTH) : reason;
    }
}
