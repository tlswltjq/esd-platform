package com.stove.common.event.payload;

import com.stove.common.event.DomainEvent;
import com.stove.common.event.EventType;
import com.stove.common.event.Topics;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** license → order : 라이브러리 지급 완료(Saga 정상 종료) */
public record LicenseIssuedEvent(
        String eventId,
        Instant occurredAt,
        String orderNo,
        Long memberId,
        List<Long> productIds
) implements DomainEvent {

    public static LicenseIssuedEvent of(String orderNo, Long memberId, List<Long> productIds) {
        return new LicenseIssuedEvent(UUID.randomUUID().toString(), Instant.now(), orderNo, memberId, productIds);
    }

    @Override
    public String eventType() {
        return EventType.LICENSE_ISSUED;
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
