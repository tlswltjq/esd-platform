package com.stove.common.event;

import java.time.Instant;

/**
 * 서비스 간 계약(contract). 모든 이벤트는
 * - eventId  : 컨슈머 멱등 처리 키
 * - eventType: 라우팅/역직렬화 기준
 * - topic    : 발행 대상
 * - partitionKey: 순서 보장 단위(주문번호 등)
 * 를 제공한다.
 */
public interface DomainEvent {

    String eventId();

    String eventType();

    String topic();

    String partitionKey();

    Instant occurredAt();
}
