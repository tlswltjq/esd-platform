package com.stove.bench.messaging.app.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 시험 표 {@code bench_message}의 한 행 = 메시지 하나의 업무 반영. {@code event_id}에 유일 제약을 두지 않는다 —
 * 중복 반영(E13 정합성)을 막는 것은 Inbox이고, 이 표는 그것이 실제로 막았는지 세는 자리다.
 */
@Entity
@Table(name = "bench_message")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BenchRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, length = 64)
    private String eventId;

    @Column(name = "message_key", nullable = false, length = 100)
    private String messageKey;

    @Column(name = "sent_at_us", nullable = false)
    private long sentAtMicros;

    /** 이 행을 쓴 시도 번호(이 프로세스 안에서 센 값, 재시작하면 1부터). */
    @Column(name = "attempt", nullable = false)
    private int attempt;

    @Column(name = "payload_bytes", nullable = false)
    private int payloadBytes;

    /** 커밋 직전 벽시계(epoch µs). 커밋 시각과는 트랜잭션 종료 비용만큼 다르다. */
    @Column(name = "completed_at_us", nullable = false)
    private long completedAtMicros;

    public static BenchRecord of(BenchMessage message, int attempt, long completedAtMicros) {
        BenchRecord record = new BenchRecord();
        record.eventId = message.eventId();
        record.messageKey = message.key();
        record.sentAtMicros = message.sentAtMicros();
        record.attempt = attempt;
        record.payloadBytes = message.payloadBytes();
        record.completedAtMicros = completedAtMicros;
        return record;
    }
}
