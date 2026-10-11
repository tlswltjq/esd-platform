package com.stove.common.event.payload;

import com.stove.common.event.DomainEvent;
import com.stove.common.event.EventType;
import com.stove.common.event.Topics;
import java.time.Instant;
import java.util.UUID;

/**
 *  studio → download : 새 빌드가 올라옴(패치 매니페스트 생성 트리거)
 *
 * @param eventId 서비스 간 전달 시 유지하는 이벤트 고유 식별자.
 * @param occurredAt 업무 행동을 기록한 시각.
 * @param gameId Studio 게임 프로젝트 식별자.
 * @param productCode 서비스 간 상품을 연결하는 고유 코드.
 * @param version 배포 파일의 제품 버전 문자열.
 * @param fileSize 배포 파일의 크기(바이트).
 * @param checksum 파일 무결성 검증에 사용하는 체크섬.
 * @param storagePath 파일이 보관된 객체 저장소 경로.
 */
public record BuildUploadedEvent(
        String eventId,
        Instant occurredAt,
        Long gameId,
        String productCode,
        String version,
        long fileSize,
        String checksum,
        String storagePath
) implements DomainEvent {

    public static BuildUploadedEvent of(Long gameId, String productCode, String version,
                                        long fileSize, String checksum, String storagePath) {
        return new BuildUploadedEvent(UUID.randomUUID().toString(), Instant.now(),
                gameId, productCode, version, fileSize, checksum, storagePath);
    }

    @Override
    public String eventType() {
        return EventType.BUILD_UPLOADED;
    }

    @Override
    public String topic() {
        return Topics.STUDIO;
    }

    @Override
    public String partitionKey() {
        return productCode;
    }
}
