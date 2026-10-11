package com.stove.common.event.payload;

import com.stove.common.event.DomainEvent;
import com.stove.common.event.EventType;
import com.stove.common.event.Topics;
import java.time.Instant;
import java.util.UUID;

/**
 * 이전 릴리스로 되돌린 사실을 다른 서비스에 전달하는 이벤트.
 *
 * @param eventId 서비스 간 전달 시 유지하는 이벤트 고유 식별자.
 * @param occurredAt 업무 행동을 기록한 시각.
 * @param releaseId 대상 릴리스 식별자.
 * @param previousReleaseId 변경 또는 롤백의 기준이 되는 이전 릴리스 식별자.
 * @param restoredBuildId 롤백으로 복구할 이전 빌드 식별자.
 * @param productCode 서비스 간 상품을 연결하는 고유 코드.
 */
public record ReleaseRolledBackEvent(String eventId, Instant occurredAt, Long releaseId,
                                     Long previousReleaseId, Long restoredBuildId, String productCode)
        implements DomainEvent {
    public static ReleaseRolledBackEvent of(Long releaseId, Long previousReleaseId,
                                            Long restoredBuildId, String productCode) {
        return new ReleaseRolledBackEvent(UUID.randomUUID().toString(), Instant.now(), releaseId,
                previousReleaseId, restoredBuildId, productCode);
    }
    @Override public String eventType() { return EventType.RELEASE_ROLLED_BACK; }
    @Override public String topic() { return Topics.STUDIO; }
    @Override public String partitionKey() { return productCode; }
}
