package com.stove.common.messaging.ops;

import com.stove.common.messaging.outbox.OutboxEvent;
import java.time.Instant;

/**
 * 발행을 포기한 이벤트 한 건. <b>운영자가 판단하는 데 필요한 것만</b> 담는다.
 *
 * <p>{@code payload} 는 넣지 않는다 — 결제 금액·회원 식별자가 들어 있어 운영 화면과 로그에
 * 그대로 퍼진다. 무엇이 실패했는지는 {@code eventType} 과 {@code aggregateId} 로 특정되고,
 * 내용이 필요하면 {@code traceParent} 로 트레이스를 열면 된다.
 *
 * @param traceParent 이 이벤트를 낳은 요청의 추적 컨텍스트. traceId 부분을 Tempo 에 넣으면
 *                    실패한 이벤트에서 그 주문의 전 구간으로 바로 갈 수 있다
 */
public record DeadEventResponse(
        String eventId,
        String eventType,
        String aggregateType,
        String aggregateId,
        String topic,
        String partitionKey,
        int retryCount,
        String lastError,
        String traceParent,
        Instant createdAt) {

    public static DeadEventResponse from(OutboxEvent event) {
        return new DeadEventResponse(
                event.getEventId(),
                event.getEventType(),
                event.getAggregateType(),
                event.getAggregateId(),
                event.getTopic(),
                event.getPartitionKey(),
                event.getRetryCount(),
                event.getLastError(),
                event.getTraceParent(),
                event.getCreatedAt());
    }
}
