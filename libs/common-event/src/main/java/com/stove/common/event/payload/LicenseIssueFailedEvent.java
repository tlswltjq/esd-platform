package com.stove.common.event.payload;

import com.stove.common.event.DomainEvent;
import com.stove.common.event.EventType;
import com.stove.common.event.Topics;
import java.time.Instant;
import java.util.UUID;

/**
 * license → payment : 결제는 성공했으나 지급에 최종 실패.
 * payment 서비스가 이 이벤트를 받아 <b>보상 트랜잭션(자동 환불)</b>을 수행한다.
 */
public record LicenseIssueFailedEvent(
        String eventId,
        Instant occurredAt,
        String orderNo,
        Long memberId,
        String reason
) implements DomainEvent {

    public static LicenseIssueFailedEvent of(String orderNo, Long memberId, String reason) {
        return new LicenseIssueFailedEvent(UUID.randomUUID().toString(), Instant.now(), orderNo, memberId, reason);
    }

    @Override
    public String eventType() {
        return EventType.LICENSE_ISSUE_FAILED;
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
