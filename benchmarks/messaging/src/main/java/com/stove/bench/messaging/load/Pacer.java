package com.stove.bench.messaging.load;

import java.util.concurrent.locks.LockSupport;
import java.util.function.LongSupplier;

/**
 * open-loop 일정. n번째 메시지의 예정 시각은 {@code 시작 + n / rate}로 미리 정해지고, 앞 메시지가 늦어도 뒤 일정이 밀리지 않는다 —
 * 시스템이 느려지면 발생기가 덜 보내는 것이 아니라 "예정보다 늦게 보냈다"가 기록된다(달성률·지연 판정, 결정 231).
 */
public final class Pacer {

    private final long startNanos;
    private final double nanosPerMessage;
    private final LongSupplier clock;

    public Pacer(double rate, long startNanos, LongSupplier clock) {
        this.startNanos = startNanos;
        this.nanosPerMessage = 1_000_000_000d / rate;
        this.clock = clock;
    }

    /** n번째 메시지의 예정 시각(단조 시계 ns). */
    public long scheduledNanos(long sequence) {
        return startNanos + Math.round(sequence * nanosPerMessage);
    }

    /** 예정 시각까지 기다린다. 이미 지났으면 바로 돌아온다. 돌아온 시각을 준다. */
    public long awaitSlot(long sequence) {
        long due = scheduledNanos(sequence);
        long now = clock.getAsLong();
        while (now < due) {
            LockSupport.parkNanos(due - now);
            now = clock.getAsLong();
        }
        return now;
    }
}
