package com.stove.common.event.payload;

import com.stove.common.event.DomainEvent;
import com.stove.common.event.EventType;
import com.stove.common.event.Topics;
import java.time.Instant;
import java.util.UUID;

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
