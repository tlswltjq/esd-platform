package com.stove.common.messaging.ops;

import java.time.Instant;

/**
 * 재발행 접수 결과. 본문은 담지 않는다({@link DeadEventResponse} 와 같은 이유).
 *
 * @param status        재발행 직후 Outbox 상태. 항상 {@code PENDING} 이며 업무 완료가 아니다
 * @param republishedBy 실행한 ADMIN 의 JWT {@code sub}
 */
public record OutboxRepublishResponse(
        String eventId,
        String eventType,
        String aggregateId,
        String status,
        String republishedBy,
        Instant republishedAt) {
}
