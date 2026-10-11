package com.stove.common.event.payload;

import com.stove.common.event.DomainEvent;
import com.stove.common.event.EventType;
import com.stove.common.event.Topics;
import java.time.Instant;
import java.util.UUID;

/**
 * 계정 가입 완료 사실을 다른 서비스에 전달하는 이벤트.
 *
 * @param eventId 서비스 간 전달 시 유지하는 이벤트 고유 식별자.
 * @param occurredAt 업무 행동을 기록한 시각.
 * @param subject 계정을 식별하는 인증 주체 문자열.
 * @param email 로그인 계정의 이메일 주소.
 */
public record UserRegisteredEvent(
        String eventId,
        Instant occurredAt,
        String subject,
        String email
) implements DomainEvent {
    public static UserRegisteredEvent of(String subject, String email) {
        return new UserRegisteredEvent(UUID.randomUUID().toString(), Instant.now(), subject, email);
    }

    @Override public String eventType() { return EventType.USER_REGISTERED; }
    @Override public String topic() { return Topics.AUTH; }
    @Override public String partitionKey() { return subject; }
}
