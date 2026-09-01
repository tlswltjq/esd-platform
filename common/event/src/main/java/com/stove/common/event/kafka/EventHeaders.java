package com.stove.common.event.kafka;

/** Kafka 메시지 헤더 규약. 컨슈머는 body 를 파싱하기 전에 헤더만 보고 라우팅/멱등 판단을 한다. */
public final class EventHeaders {

    public static final String EVENT_ID = "eventId";
    public static final String EVENT_TYPE = "eventType";
    public static final String OCCURRED_AT = "occurredAt";

    /**
     * W3C Trace Context. <b>이름을 우리가 정하지 않는다</b> — 표준이라 바꾸면 추적이 조용히 끊긴다.
     * 자동 계측만으로는 채워지지 않는다(Outbox 의 발행자는 요청 스레드가 아니다). docs/code-notes.md
     */
    public static final String TRACE_PARENT = "traceparent";

    private EventHeaders() {
    }
}
