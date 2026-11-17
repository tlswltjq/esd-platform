package com.stove.common.messaging.trace;

/**
 * 격리된 메시지를 다시 넣을 때 원래 메시지의 추적 컨텍스트 안에서 처리한다(PR #209 R4).
 * 처리 중에 Outbox 로 남기는 후속 이벤트가 운영 요청이 아니라 원래 흐름의 트레이스에 이어진다.
 */
@FunctionalInterface
public interface ReplayTracing {

    /** 추적이 없는 실행. */
    ReplayTracing NONE = (traceParent, work) -> work.run();

    /** @param traceParent 원래 메시지의 {@code traceparent}. 없으면 {@code null} */
    void runWithin(String traceParent, Runnable work);
}
