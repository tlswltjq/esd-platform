package com.stove.common.event.payload;

import com.stove.common.event.DomainEvent;
import com.stove.common.event.EventType;
import com.stove.common.event.Topics;
import java.time.Instant;
import java.util.UUID;

/**
 * 빌드 검증 실패 사실을 다른 서비스에 전달하는 이벤트.
 *
 * @param eventId 서비스 간 전달 시 유지하는 이벤트 고유 식별자.
 * @param occurredAt 업무 행동을 기록한 시각.
 * @param buildId 배포 파일을 나타내는 빌드 식별자.
 * @param gameId Studio 게임 프로젝트 식별자.
 * @param productCode 서비스 간 상품을 연결하는 고유 코드.
 * @param failureCode 빌드 검증 실패의 분류 코드.
 */
public record BuildValidationFailedEvent(
        String eventId,
        Instant occurredAt,
        Long buildId,
        Long gameId,
        String productCode,
        String failureCode
) implements DomainEvent {

    public static BuildValidationFailedEvent of(Long buildId, Long gameId, String productCode,
                                                String failureCode) {
        return new BuildValidationFailedEvent(UUID.randomUUID().toString(), Instant.now(),
                buildId, gameId, productCode, failureCode);
    }

    @Override public String eventType() { return EventType.BUILD_VALIDATION_FAILED; }
    @Override public String topic() { return Topics.STUDIO; }
    @Override public String partitionKey() { return productCode; }
}
