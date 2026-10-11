package com.stove.common.event.payload;

import com.stove.common.event.DomainEvent;
import com.stove.common.event.EventType;
import com.stove.common.event.Topics;
import java.time.Instant;
import java.util.UUID;

/**
 * 릴리스 공개 예약 사실을 다른 서비스에 전달하는 이벤트.
 *
 * @param eventId 서비스 간 전달 시 유지하는 이벤트 고유 식별자.
 * @param occurredAt 업무 행동을 기록한 시각.
 * @param releaseId 대상 릴리스 식별자.
 * @param submissionId 심사할 자료와 빌드를 묶은 제출물 식별자.
 * @param productCode 서비스 간 상품을 연결하는 고유 코드.
 * @param publishAt 릴리스를 공개하도록 예약한 시각.
 */
public record ReleaseScheduledEvent(String eventId, Instant occurredAt, Long releaseId,
                                    Long submissionId, String productCode, Instant publishAt)
        implements DomainEvent {
    public static ReleaseScheduledEvent of(Long releaseId, Long submissionId,
                                           String productCode, Instant publishAt) {
        return new ReleaseScheduledEvent(UUID.randomUUID().toString(), Instant.now(),
                releaseId, submissionId, productCode, publishAt);
    }
    @Override public String eventType() { return EventType.RELEASE_SCHEDULED; }
    @Override public String topic() { return Topics.STUDIO; }
    @Override public String partitionKey() { return productCode; }
}
