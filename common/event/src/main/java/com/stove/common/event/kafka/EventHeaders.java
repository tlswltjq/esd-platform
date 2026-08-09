package com.stove.common.event.kafka;

/** Kafka 메시지 헤더 규약. 컨슈머는 body 를 파싱하기 전에 헤더만 보고 라우팅/멱등 판단을 한다. */
public final class EventHeaders {

    public static final String EVENT_ID = "eventId";
    public static final String EVENT_TYPE = "eventType";
    public static final String OCCURRED_AT = "occurredAt";

    /**
     * W3C Trace Context 의 추적 컨텍스트. 형식은 {@code 00-<traceId>-<spanId>-<flags>} 고정 55자다.
     *
     * <p><b>이름을 우리가 정하지 않는다</b> — 위의 셋과 달리 이 헤더는 표준이다.
     * 수신 측 계측(Spring Kafka 옵저베이션)이 이 이름으로 찾으므로 값을 바꾸면 추적이 조용히 끊긴다.
     *
     * <p>여기 있던 {@code correlationId} 를 이 헤더가 대신한다. 그 상수의 주석은
     * "Micrometer Tracing 이 들어오면 손으로 만든 헤더는 중복이 되므로 규약만 남기고 미룬다"
     * 였고, 실제로 그렇게 됐다 — traceId 가 correlationId 의 역할을 그대로 하면서
     * 구간별 소요 시간과 부모-자식 관계까지 얹어 준다.
     *
     * <p><b>다만 자동 계측만으로는 이 헤더가 채워지지 않는다.</b> 계측은 {@code send()} 를 호출한
     * 스레드의 컨텍스트를 싣는데, Outbox 의 발행자는 요청 스레드가 아니라 릴레이 스케줄러이기 때문이다.
     * 그래서 적재 시점에 붙잡아 두었다가 발행 시점에 되살린다
     * ({@code common:messaging} 의 {@code TraceContextCapture} 에 그 이유를 적어 두었다 —
     * 이 모듈은 messaging 을 모르므로 링크가 아니라 이름으로 가리킨다).
     */
    public static final String TRACE_PARENT = "traceparent";

    private EventHeaders() {
    }
}
