package com.stove.common.kafka.contract.payload;

import com.stove.common.kafka.contract.DomainEvent;
import com.stove.common.kafka.contract.EventType;
import com.stove.common.kafka.contract.Topics;
import java.time.Instant;
import java.util.UUID;

/**
 * 빌드 검증 성공 사실을 다른 서비스에 전달하는 이벤트.
 *
 * @param eventId 서비스 간 전달 시 유지하는 이벤트 고유 식별자.
 * @param occurredAt 업무 행동을 기록한 시각.
 * @param buildId 배포 파일을 나타내는 빌드 식별자.
 * @param gameId Studio 게임 프로젝트 식별자.
 * @param productCode 서비스 간 상품을 연결하는 고유 코드.
 * @param checksum 파일 무결성 검증에 사용하는 체크섬.
 * @param platform 빌드가 실행될 대상 플랫폼.
 * @param productVersion 빌드의 제품 버전 문자열.
 * @param buildNumber 같은 제품 버전 내 빌드를 구별하는 번호.
 * @param commitSha 빌드에 사용한 소스 커밋 식별자.
 */
public record BuildValidatedEvent(
        String eventId,
        Instant occurredAt,
        Long buildId,
        Long gameId,
        String productCode,
        String checksum,
        String platform,
        String productVersion,
        String buildNumber,
        String commitSha
) implements DomainEvent {

    public static BuildValidatedEvent of(Long buildId, Long gameId, String productCode,
                                         String checksum, String platform, String productVersion,
                                         String buildNumber, String commitSha) {
        return new BuildValidatedEvent(UUID.randomUUID().toString(), Instant.now(), buildId, gameId,
                productCode, checksum, platform, productVersion, buildNumber, commitSha);
    }

    @Override public String eventType() { return EventType.BUILD_VALIDATED; }
    @Override public String topic() { return Topics.STUDIO; }
    @Override public String partitionKey() { return productCode; }
}
