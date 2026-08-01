package com.stove.common.event.payload;

import com.stove.common.event.DomainEvent;
import com.stove.common.event.EventType;
import com.stove.common.event.Topics;
import java.time.Instant;
import java.util.UUID;

/** studio → download : 새 빌드가 올라옴(패치 매니페스트 생성 트리거) */
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
