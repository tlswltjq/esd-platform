package com.stove.common.event.kafka;

/** Kafka 메시지 헤더 규약. 컨슈머는 body 를 파싱하기 전에 헤더만 보고 라우팅/멱등 판단을 한다. */
public final class EventHeaders {

    public static final String EVENT_ID = "eventId";
    public static final String EVENT_TYPE = "eventType";
    public static final String OCCURRED_AT = "occurredAt";
    public static final String CORRELATION_ID = "correlationId";

    private EventHeaders() {
    }
}
