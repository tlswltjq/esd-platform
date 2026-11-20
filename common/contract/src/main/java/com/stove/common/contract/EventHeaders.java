package com.stove.common.contract;

/** Kafka 메시지 헤더 규약. 컨슈머는 body 를 파싱하기 전에 헤더만 보고 라우팅/멱등 판단을 한다. */
public final class EventHeaders {

    public static final String EVENT_ID = "eventId";
    public static final String EVENT_TYPE = "eventType";
    public static final String OCCURRED_AT = "occurredAt";

    /**
     * 릴레이가 브로커로 보내기 직전 시각(ISO-8601). 브로커 구간 지연 측정용이며 업무 판단에 쓰지 않는다([결정 204] B).
     * 행의 {@code outbox_event.published_at}과 같은 값이다. 브로커 구현과 무관하게 같은 이름으로 싣는다.
     */
    public static final String PUBLISHED_AT = "x-published-at";

    /**
     * W3C Trace Context. <b>이름을 우리가 정하지 않는다</b> — 표준이라 바꾸면 추적이 조용히 끊긴다.
     * 자동 계측만으로는 채워지지 않는다(Outbox 의 발행자는 요청 스레드가 아니다).
     */
    public static final String TRACE_PARENT = "traceparent";

    private EventHeaders() {
    }
}
