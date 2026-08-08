package com.stove.common.event.kafka;

/** Kafka 메시지 헤더 규약. 컨슈머는 body 를 파싱하기 전에 헤더만 보고 라우팅/멱등 판단을 한다. */
public final class EventHeaders {

    public static final String EVENT_ID = "eventId";
    public static final String EVENT_TYPE = "eventType";
    public static final String OCCURRED_AT = "occurredAt";

    /**
     * <b>아직 실어 보내지 않는다.</b> 적재({@code OutboxRecorder})·발행({@code OutboxRelay})·
     * 수신({@link EventEnvelope}) 세 군데 모두 미구현이다.
     *
     * <p>결과적으로 correlationId 는 HTTP 구간에서만 이어진다. 주문 하나가
     * order → payment → license → settlement 로 흐를 때 로그를 이 값으로 묶으면
     * order 에서 끊기고, 그 뒤로는 로그 패턴이 {@code [payment,]} 로 빈칸을 찍는다.
     *
     * <p>직접 구현하려면 Outbox 컬럼과 서비스별 마이그레이션이 필요하다 — MDC 는 스레드 로컬이라
     * <b>적재 시점(요청 스레드)에 담아 두지 않으면</b> 릴레이(스케줄러 스레드)에서는 이미 없다.
     * 그런데 README 향후 과제의 Micrometer Tracing 이 들어오면 Kafka 계측이 전파를 자동으로 하므로
     * 손으로 만든 헤더는 그때 중복이 된다. <b>그래서 지금은 규약만 남기고 백로그로 미룬다.</b>
     */
    public static final String CORRELATION_ID = "correlationId";

    private EventHeaders() {
    }
}
