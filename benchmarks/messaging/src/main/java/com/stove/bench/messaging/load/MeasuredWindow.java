package com.stove.bench.messaging.load;

/**
 * 발생기 달성률을 재는 고정 측정 창(결정 231: 부하 발생기가 목표 요청률을 냈는가). 창은 예정 시각으로 정한다 —
 * {@code [측정 첫 메시지의 예정 시각, + 측정 시간)}. 실제로 보낸 시각이 창 안인 측정 메시지 수 / 측정 시간이 달성률이다.
 * 예열에서 밀린 일정이 측정 구간을 통째로 늦춰도 창이 따라 움직이지 않으므로 미달로 드러난다.
 */
final class MeasuredWindow {

    private final long startNanos;
    private final long endNanos;
    private final int seconds;
    private long inWindow;
    private long afterWindow;

    MeasuredWindow(long startNanos, int seconds) {
        this.startNanos = startNanos;
        this.endNanos = startNanos + seconds * 1_000_000_000L;
        this.seconds = seconds;
    }

    /** 측정 메시지 하나를 {@code requestNanos}(단조 시계)에 보냈다. */
    void sent(long requestNanos) {
        if (requestNanos < endNanos) {
            inWindow++;
        } else {
            afterWindow++;
        }
    }

    long startNanos() {
        return startNanos;
    }

    long inWindow() {
        return inWindow;
    }

    long afterWindow() {
        return afterWindow;
    }

    double achievedRate() {
        return (double) inWindow / seconds;
    }
}
