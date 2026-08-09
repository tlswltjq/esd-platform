package com.stove.common.messaging.trace;

/**
 * 지금 실행 중인 흐름의 추적 컨텍스트를 <b>문자열로</b> 붙잡는다.
 *
 * <p>이 인터페이스가 존재하는 이유는 Transactional Outbox 와 분산 추적이 구조적으로 충돌하기 때문이다.
 * 추적 라이브러리의 Kafka 자동 계측은 {@code send()} 를 <b>호출한 스레드</b>에 매달린 컨텍스트를
 * 헤더에 싣는다. 그런데 Outbox 는 발행을 일부러 뒤로 미루므로 실제 발행자는 요청 스레드가 아니라
 * 릴레이 스케줄러다 — 자동 계측에 맡기면 컨슈머가 "릴레이가 돈 폴링 사이클"을 부모로 삼는다.
 * 한 배치에 여러 주문이 섞이면 서로 다른 주문이 같은 traceId 를 공유하는 더 나쁜 상태가 된다.
 *
 * <p>그래서 컨텍스트를 <b>이벤트와 같은 트랜잭션에서 같이 저장</b>했다가 발행 시점에 되살린다.
 * 이벤트 본문에 적용한 논리(지금 커밋하고 나중에 보낸다)를 추적 컨텍스트에도 그대로 적용하는 것이다.
 *
 * <p>붙잡는 형식은 W3C Trace Context 의 {@code traceparent} 한 줄이다. 표준이라 수신 측이
 * 무엇으로 구현됐든 읽을 수 있고, 55자 고정 길이라 컬럼 하나로 끝난다.
 *
 * @see com.stove.common.messaging.outbox.OutboxRecorder 붙잡는 자리(요청 스레드)
 * @see com.stove.common.messaging.outbox.OutboxRelay 되살리는 자리(스케줄러 스레드)
 */
@FunctionalInterface
public interface TraceContextCapture {

    /**
     * 추적이 꺼져 있거나 지금 진행 중인 스팬이 없으면 {@code null} 을 돌려주는 <b>비활성 구현</b>.
     *
     * <p>추적 설정 여부가 이벤트 발행 가능 여부를 좌우해서는 안 된다 —
     * {@code OutboxMetrics} 가 레지스트리 없이도 도는 것과 같은 이유다.
     */
    TraceContextCapture DISABLED = () -> null;

    /**
     * 현재 흐름의 {@code traceparent}. 추적이 없거나 진행 중인 스팬이 없으면 {@code null}.
     *
     * <p>반드시 <b>요청 스레드에서</b> 불러야 한다. 추적 컨텍스트는 스레드 로컬이라
     * 트랜잭션이 끝나고 스레드가 반납되면 이미 사라진 뒤다.
     */
    String captureTraceParent();
}
