package com.stove.bench.messaging.app.core.domain;

/** {@link FailurePlan}이 정한 시도에서 던지는 주입 실패. 원인 예외 이름으로 {@code failed-deliveries} 지표에 남는다. */
public class BenchInjectedFailure extends RuntimeException {

    public BenchInjectedFailure(String eventId, int attempt, FailurePlan plan) {
        super("주입 실패 eventId=%s attempt=%d plan=%s".formatted(eventId, attempt, plan));
    }
}
