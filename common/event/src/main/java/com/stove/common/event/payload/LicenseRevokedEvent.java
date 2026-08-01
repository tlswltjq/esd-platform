package com.stove.common.event.payload;

import com.stove.common.event.DomainEvent;
import com.stove.common.event.EventType;
import com.stove.common.event.Topics;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** license → download : 소유권 회수(다운로드 권한 박탈) */
public record LicenseRevokedEvent(
        String eventId,
        Instant occurredAt,
        String orderNo,
        Long memberId,
        List<Long> productIds,
        String reason
) implements DomainEvent {

    public static LicenseRevokedEvent of(String orderNo, Long memberId, List<Long> productIds, String reason) {
        return new LicenseRevokedEvent(UUID.randomUUID().toString(), Instant.now(),
                orderNo, memberId, productIds, reason);
    }

    @Override
    public String eventType() {
        return EventType.LICENSE_REVOKED;
    }

    @Override
    public String topic() {
        return Topics.LICENSE;
    }

    @Override
    public String partitionKey() {
        return orderNo;
    }
}
