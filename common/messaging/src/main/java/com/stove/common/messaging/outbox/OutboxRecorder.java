package com.stove.common.messaging.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.kafka.contract.DomainEvent;
import com.stove.common.messaging.trace.TraceContextCapture;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 도메인 이벤트를 Outbox 에 적재한다.
 * {@code Propagation.MANDATORY} 로 선언해 <b>반드시 비즈니스 트랜잭션 안에서만</b> 호출되도록 강제한다.
 * (실수로 트랜잭션 밖에서 부르면 즉시 예외 → 원자성 보장 규칙을 컴파일 이후에도 지킬 수 있음)
 *
 * <p>현재 실행 scope의 추적 컨텍스트를 이벤트와 함께 저장한다. 트랜잭션 자체는
 * 요청 컨텍스트를 보장하지 않는다. DB에 보관한 업무를 스케줄러가 처리한다면 호출자가
 * 업무별 추적 scope를 먼저 복원해야 한다. 릴레이는 저장된 컨텍스트를 헤더로 전달한다.
 */
@RequiredArgsConstructor
public class OutboxRecorder {

    private final OutboxEventRepository repository;
    private final ObjectMapper objectMapper;
    private final TraceContextCapture traceContextCapture;

    /** 추적을 구성하지 않은 서비스·테스트용. 적재는 그대로 되고 traceparent 만 비어 있다. */
    public OutboxRecorder(OutboxEventRepository repository, ObjectMapper objectMapper) {
        this(repository, objectMapper, TraceContextCapture.DISABLED);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String aggregateType, String aggregateId, DomainEvent event) {
        repository.save(OutboxEvent.pending(
                event.eventId(),
                aggregateType,
                aggregateId,
                event.eventType(),
                event.topic(),
                event.partitionKey(),
                serialize(event),
                traceContextCapture.captureTraceParent()));
    }

    private String serialize(DomainEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("이벤트 직렬화 실패: " + event.eventType(), e);
        }
    }
}
