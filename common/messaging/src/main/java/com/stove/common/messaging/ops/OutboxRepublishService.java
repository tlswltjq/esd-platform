package com.stove.common.messaging.ops;

import com.stove.common.messaging.outbox.OutboxEvent;
import com.stove.common.messaging.outbox.OutboxEvent.OutboxStatus;
import com.stove.common.messaging.outbox.OutboxEventRepository;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.transaction.annotation.Transactional;

/**
 * Kafka 에서 원본이 사라진 SENT 이벤트를 원래 Outbox 행 그대로 다시 내보낸다([결정 129]).
 *
 * <p>행의 {@code event_id}·본문·키는 바꾸지 않고 상태만 발행 대기로 돌린다. 새 이벤트 ID 를 만들지 않는다.
 * 실행자·시각·사유·이전 발행 시각은 같은 트랜잭션에서 {@code outbox_republish} 에 남긴다.
 * 그 표는 재발행을 켠 모듈만 가진다({@code stove.messaging.ops.republish-enabled}).
 */
@Slf4j
@RequiredArgsConstructor
public class OutboxRepublishService {

    private static final int REASON_MAX = 500;
    private static final int OPERATOR_MAX = 100;
    private static final String RECORD = """
            INSERT INTO outbox_republish
                (event_id, event_type, previous_sent_at, republished_by, reason, republished_at)
            VALUES (?, ?, ?, ?, ?, ?)
            """;

    private final OutboxEventRepository repository;
    private final JdbcOperations jdbc;
    private final Clock clock;

    /**
     * 한 건을 재발행 대기로 돌린다.
     *
     * @throws BusinessException 사유·실행자가 없거나 허용 목록 밖의 타입이면 {@code INVALID_REQUEST},
     *                           행이 없거나 SENT 가 아니면 {@code NOT_FOUND}(DEAD 회수와 같은 규약)
     */
    @Transactional
    public OutboxRepublishResponse republish(String eventId, String operator, String reason) {
        requireOperator(operator);
        requireReason(reason);
        OutboxEvent event = repository.lockByEventId(eventId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Outbox 이벤트가 없다: " + eventId));
        if (!OutboxRepublishTargets.allows(event.getEventType())) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "재발행을 허용하지 않는 이벤트 타입이다: " + event.getEventType());
        }
        if (event.getStatus() != OutboxStatus.SENT) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "SENT 상태의 이벤트가 아니다: " + eventId);
        }
        Instant previousSentAt = event.getSentAt();
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        event.republish();
        jdbc.update(RECORD, event.getEventId(), event.getEventType(),
                previousSentAt == null ? null : Timestamp.from(previousSentAt),
                operator, reason, Timestamp.from(now));
        log.warn("운영 재발행 — SENT outbox 이벤트를 같은 ID 로 발행 대기로 돌린다 eventId={} type={} operator={}",
                event.getEventId(), event.getEventType(), operator);
        return new OutboxRepublishResponse(event.getEventId(), event.getEventType(), event.getAggregateId(),
                event.getStatus().name(), operator, now);
    }

    private static void requireOperator(String operator) {
        if (operator == null || operator.isBlank() || operator.length() > OPERATOR_MAX) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "실행자를 확인할 수 없다");
        }
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.isBlank() || reason.length() > REASON_MAX) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "재발행 사유는 1~500자로 적는다");
        }
    }
}
