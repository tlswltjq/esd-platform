package com.stove.payment.core.domain;

import java.time.Duration;

/**
 * 중단된 취소를 다시 걸기까지의 백오프(2→4→8→16→30분).
 * 계수가 {@code ConsumerRetryPolicy} 와 다른 이유와 상한의 근거는 docs/code-notes.md
 */
public final class RefundRetryPolicy {

    /** 최초 유예. 진행 중인 건을 옆에서 다시 부르지 않을 만큼만 둔다. */
    private static final Duration INITIAL = Duration.ofMinutes(2);
    private static final Duration MAX = Duration.ofMinutes(30);
    private static final int MULTIPLIER = 2;

    private RefundRetryPolicy() {
    }

    /**
     * {@code attempts} 번 시도한 뒤 다음까지 기다릴 시간.
     * 지수를 상한에서 끊는다 — 재시도가 끝나지 않는 설계라 <b>이 값은 실제로 커진다.</b>
     */
    public static Duration backoffAfter(int attempts) {
        Duration delay = INITIAL;
        for (int i = 0; i < attempts && delay.compareTo(MAX) < 0; i++) {
            delay = delay.multipliedBy(MULTIPLIER);
        }
        return delay.compareTo(MAX) > 0 ? MAX : delay;
    }

    /** 착수 직후의 첫 유예. {@link PaymentProperties#refundResumeAfter()} 가 이 값을 대신 정한다. */
    public static Duration initial() {
        return INITIAL;
    }
}
