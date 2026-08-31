package com.stove.common.messaging.trace;

/**
 * 지금 실행 중인 흐름의 추적 컨텍스트를 <b>문자열로</b> 붙잡는다.
 * Transactional Outbox 와 분산 추적이 구조적으로 충돌하기 때문에 있다 — docs/code-notes.md
 *
 * @see com.stove.common.messaging.outbox.OutboxRecorder 붙잡는 자리(요청 스레드)
 * @see com.stove.common.messaging.outbox.OutboxRelay 되살리는 자리(스케줄러 스레드)
 */
@FunctionalInterface
public interface TraceContextCapture {

    /** 비활성 구현. <b>추적 설정 여부가 이벤트 발행 가능 여부를 좌우해서는 안 된다.</b> */
    TraceContextCapture DISABLED = () -> null;

    /**
     * 현재 흐름의 {@code traceparent}. 없으면 {@code null}.
     * <b>반드시 요청 스레드에서 불러야 한다</b> — 스레드 로컬이라 나중에는 사라진 뒤다.
     */
    String captureTraceParent();
}
