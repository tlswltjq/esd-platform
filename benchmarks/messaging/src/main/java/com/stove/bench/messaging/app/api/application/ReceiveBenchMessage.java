package com.stove.bench.messaging.app.api.application;

import com.stove.bench.messaging.app.core.domain.Attempt;
import com.stove.bench.messaging.app.core.domain.Attempt.Outcome;
import com.stove.bench.messaging.app.core.domain.BenchMessage;
import com.stove.bench.messaging.app.core.port.AttemptLog;
import com.stove.bench.messaging.app.core.service.RecordBenchMessage;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 시도 하나를 트랜잭션 <b>밖</b>에서 감싸 수신 시도 기록을 남긴다(E13). 시도 번호는 메시지 ID별로 이 프로세스 안에서 센다 —
 * 제자리 재시도는 같은 소비자 스레드에서 같은 레코드를 다시 부르므로 이 수가 재시도 횟수 + 1이다.
 *
 * <p>반영·중복이면 그 메시지의 수를 지운다(메모리가 처리량에 비례해 늘지 않게). 실패로 남은 수는 격리된 메시지 몫만 남는다.
 * 프로세스를 다시 띄우면 수는 1부터 다시 시작한다.
 */
public class ReceiveBenchMessage {

    private final RecordBenchMessage service;
    private final AttemptLog attempts;
    private final Clock clock;
    private final Map<String, Integer> attemptsSoFar = new ConcurrentHashMap<>();

    public ReceiveBenchMessage(RecordBenchMessage service, AttemptLog attempts, Clock clock) {
        this.service = service;
        this.attempts = attempts;
        this.clock = clock;
    }

    public void receive(BenchMessage message, int partition, long offset) {
        long receivedAt = nowMicros();
        int attempt = attemptsSoFar.merge(message.eventId(), 1, Integer::sum);
        try {
            boolean applied = service.execute(message, attempt);
            attemptsSoFar.remove(message.eventId());
            attempts.record(new Attempt(message.eventId(), attempt, partition, offset, receivedAt, nowMicros(),
                    applied ? Outcome.APPLIED : Outcome.DUPLICATE, ""));
        } catch (RuntimeException e) {
            attempts.record(new Attempt(message.eventId(), attempt, partition, offset, receivedAt, nowMicros(),
                    Outcome.FAILED, rootCause(e).getClass().getSimpleName()));
            throw e;
        }
    }

    private long nowMicros() {
        return ChronoUnit.MICROS.between(Instant.EPOCH, clock.instant());
    }

    private static Throwable rootCause(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }
}
