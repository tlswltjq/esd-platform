package com.stove.bench.messaging.app.core.domain;

/**
 * 헤더 {@code x-bench-fail}이 정한 주입 실패(E13 층 2). 처리 경로(시험 표 기록) 안에서 던져 실제 롤백·재시도·격리를 거친다.
 *
 * @param transientFailures 앞에서부터 실패할 시도 수(0이면 실패 없음). {@code permanent}면 쓰지 않는다.
 * @param permanent 모든 시도가 실패한다.
 */
public record FailurePlan(int transientFailures, boolean permanent) {

    public static final String HEADER = "x-bench-fail";
    public static final FailurePlan NONE = new FailurePlan(0, false);
    /** 제자리 재시도는 총 4회다. 그보다 크게 주면 일시 실패가 아니라 격리가 되므로 받지 않는다. */
    static final int MAX_TRANSIENT = 3;

    public FailurePlan {
        if (permanent && transientFailures != 0) {
            throw new IllegalArgumentException("영구 실패에는 일시 실패 수를 주지 않는다");
        }
        if (transientFailures < 0 || transientFailures > MAX_TRANSIENT) {
            throw new IllegalArgumentException("일시 실패 수는 0~" + MAX_TRANSIENT + "이다: " + transientFailures);
        }
    }

    /** {@code null}·빈 값 = 실패 없음, {@code transient:N}(N=1~3), {@code permanent}. 그 밖은 거절한다. */
    public static FailurePlan parse(String header) {
        if (header == null || header.isBlank()) {
            return NONE;
        }
        if (header.equals("permanent")) {
            return new FailurePlan(0, true);
        }
        if (header.startsWith("transient:")) {
            try {
                return new FailurePlan(Integer.parseInt(header.substring("transient:".length())), false);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(HEADER + " 형식이 아니다: " + header, e);
            }
        }
        throw new IllegalArgumentException(HEADER + " 형식이 아니다: " + header);
    }

    /** {@code attempt}(1부터)번째 시도가 실패해야 하는가. */
    public boolean failsOn(int attempt) {
        if (attempt < 1) {
            throw new IllegalArgumentException("시도 번호는 1부터다: " + attempt);
        }
        return permanent || attempt <= transientFailures;
    }
}
