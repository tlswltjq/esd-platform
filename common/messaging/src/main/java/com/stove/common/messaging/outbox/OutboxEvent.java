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

    /**
     * 적재 시점의 W3C traceparent({@code 00-<traceId>-<spanId>-<flags>}). 추적이 꺼져 있으면 {@code null}.
     *
     * <p><b>이 컬럼이 이 표에서 유일하게 "이벤트에 관한 것이 아닌" 값이다.</b> 그런데도 여기 있는 이유는
     * 추적 컨텍스트가 스레드 로컬이기 때문이다 — 적재는 요청 스레드에서, 발행은 릴레이 스케줄러에서
     * 일어나므로 붙잡아 두지 않으면 발행 시점에는 이미 사라진 뒤다. 이벤트 본문을 지금 저장했다가
     * 나중에 보내는 것과 같은 논리를 컨텍스트에도 적용한다.
     *
     * <p>{@code retryCount} 나 {@code nextAttemptAt} 과 달리 <b>재시도해도 바뀌지 않는다.</b>
     * 이 이벤트를 낳은 요청은 하나뿐이고, 몇 번째 시도에 나갔는지는 그 사실을 바꾸지 않는다.
     * 회수({@link #requeue()})도 이 값을 건드리지 않는다.
     */
    @Column(length = 64)
    private String traceParent;

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
     * 같은 키의 앞 이벤트가 재시도 대기에 들어가서 함께 보류된다.
     *
     * <p>이게 없으면 <b>회차를 넘길 때 순서가 뒤집힌다</b>(D-014). 앞 이벤트는 백오프로
     * {@code next_attempt_at} 이 미래로 밀려 조회에서 빠지는데, 뒤 이벤트는 NULL 이라 계속 잡힌다.
     * 그러면 다음 폴링에서 뒤 이벤트만 혼자 배치에 들어와 먼저 발행된다 —
     * 한 배치 안의 순서를 아무리 지켜도 배치에 같이 오지 않으면 소용이 없다.
     *
     * <p><b>{@code retryCount} 는 늘리지 않는다.</b> 이 이벤트가 실패한 것이 아니라
     * 순서 때문에 양보한 것이다. 시도해 보지도 않고 재시도 예산을 소진해 DEAD 가 되면 안 된다.
     *
     * <p>앞 이벤트가 DEAD 로 떨어지면({@code until} 이 null) 보류하지 않는다.
     * 영원히 나가지 않을 이벤트 뒤에 키 전체를 묶어두면 그 키가 영구 정지하기 때문이다.
     * DEAD 는 설계상 탈출구이고, 그 대가로 순서 보장을 포기하는 지점이다({@code docs/event-ordering.md} 6절 A-2).
     */
    public void holdUntil(Instant until) {
        if (this.status != OutboxStatus.PENDING || until == null) {
            return;
        }
        // 이미 더 뒤로 밀려 있으면 그대로 둔다 — 앞 이벤트보다 먼저 나가지만 않으면 된다.
        if (this.nextAttemptAt == null || this.nextAttemptAt.isBefore(until)) {
            this.nextAttemptAt = until;
        }
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
        PENDING,
        /** 브로커 ack 완료 */
        SENT,
        /** 재시도 한계 초과 → 운영 확인 대상 */
        DEAD
    }
}
