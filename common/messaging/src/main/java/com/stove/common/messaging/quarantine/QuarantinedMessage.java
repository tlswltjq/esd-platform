package com.stove.common.messaging.quarantine;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
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
    static final int KEY_LENGTH = 100;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String consumerGroup;

    @Column(nullable = false, length = 100)
    private String topic;

    /**
     * 정지·조회용 순서 키. 키가 없으면 빈 문자열(그런 메시지끼리 한 줄로 멈춘다), 100자를 넘으면 {@code sha256:<hex>}.
     * 깨진 메시지도 보관이 실패하지 않아야 파티션이 막히지 않는다(PR #209 R5).
     */
    @Column(nullable = false, length = KEY_LENGTH)
    private String partitionKey;

    /** 원래 키 그대로(재투입 때 같은 메시지를 만든다). */
    @Column(columnDefinition = "TEXT")
    private String messageKey;

    @Column(length = 64)
    private String eventId;

    @Column(length = 60)
    private String eventType;

    @Column(columnDefinition = "MEDIUMTEXT")
    private String payload;

    /** 받은 헤더 전부(JSON, 이름 → 값 목록). 다시 넣을 때 같은 메시지를 만든다. */
    @Column(nullable = false, columnDefinition = "MEDIUMTEXT")
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

    /** 재투입이 이 행을 점유한 시각. 점유 확인(펜싱)과 오래된 점유 회수에 쓴다. */
    private Instant claimedAt;

    private Instant replayedAt;

    static QuarantinedMessage held(String consumerGroup, String topic, String messageKey, String eventId,
                                   String eventType, String payload, String headers, String origin, String failure) {
        QuarantinedMessage message = new QuarantinedMessage();
        message.consumerGroup = consumerGroup;
        message.topic = topic;
        message.partitionKey = stopKey(messageKey);
        message.messageKey = messageKey;
        message.eventId = cut(eventId, 64);
        message.eventType = cut(eventType, 60);
        message.payload = payload;
        message.headers = headers;
        message.origin = origin.length() > 200 ? origin.substring(0, 200) : origin;
        message.failure = failure.length() > FAILURE_LENGTH ? failure.substring(0, FAILURE_LENGTH) : failure;
        message.status = QuarantineStatus.HELD;
        message.quarantinedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        return message;
    }

    /** 정지·조회에 쓰는 키. 저장할 때와 확인할 때 같은 규칙이어야 한다. */
    static String stopKey(String key) {
        if (key == null) {
            return "";
        }
        if (key.length() <= KEY_LENGTH) {
            return key;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String cut(String value, int length) {
        return value == null || value.length() <= length ? value : value.substring(0, length);
    }

    void claim(Instant at) {
        this.status = QuarantineStatus.REPLAYING;
        this.claimedAt = at;
    }

    /** 이 재투입이 아직 점유하고 있는가(오래된 점유를 다른 재투입이 회수했으면 false). */
    boolean claimedBy(Instant at) {
        return status == QuarantineStatus.REPLAYING && at.equals(claimedAt);
    }

    void markReplayed() {
        this.status = QuarantineStatus.REPLAYED;
        this.replayedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    /** 다시 실패했다. 키 정지를 유지하고 사유를 남긴다. */
    void release(String reason) {
        this.status = QuarantineStatus.HELD;
        this.claimedAt = null;
        this.failure = cut(reason, FAILURE_LENGTH);
    }
}
