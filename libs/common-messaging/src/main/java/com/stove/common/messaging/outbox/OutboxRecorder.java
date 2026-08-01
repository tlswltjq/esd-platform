package com.stove.common.messaging.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.event.DomainEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 도메인 이벤트를 Outbox 에 적재한다.
 * {@code Propagation.MANDATORY} 로 선언해 <b>반드시 비즈니스 트랜잭션 안에서만</b> 호출되도록 강제한다.
 * (실수로 트랜잭션 밖에서 부르면 즉시 예외 → 원자성 보장 규칙을 컴파일 이후에도 지킬 수 있음)
 */
@RequiredArgsConstructor
public class OutboxRecorder {

    private final OutboxEventRepository repository;
    private final ObjectMapper objectMapper;

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String aggregateType, String aggregateId, DomainEvent event) {
        repository.save(OutboxEvent.pending(
                event.eventId(),
                aggregateType,
                aggregateId,
                event.eventType(),
                event.topic(),
                event.partitionKey(),
                serialize(event)));
    }

    private String serialize(DomainEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("이벤트 직렬화 실패: " + event.eventType(), e);
        }
    }
}
