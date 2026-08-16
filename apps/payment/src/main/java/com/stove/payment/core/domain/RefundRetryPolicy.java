package com.stove.payment.core.domain;

import java.time.Duration;

/**
 * 중단된 취소를 다시 걸기까지 얼마나 기다릴 것인가.
 *
 * <p><b>왜 정책이 필요한가</b> — 이전 동작은 "1분마다 조건 없이" 였다. PG 가 죽어 있으면
 * 같은 건에 같은 간격으로 영원히 요청이 나가고, <b>복구 중인 PG 를 계속 두드린다.</b>
 * 멈추는 것은 알람뿐인데 알람은 사람을 부를 뿐 재시도를 멈추지 않는다.
 *
 * <p>{@code common:kafka} 의 {@link com.stove.common.kafka.ConsumerRetryPolicy} 와 형태가 같다 —
 * 지수 백오프에 상한. 계수가 다른 이유는 <b>막으려는 것이 다르기 때문</b>이다.
 * 컨슈머 재시도는 파티션을 세워 두므로 총 대기가 {@code max.poll.interval.ms} 안에 들어와야 하고,
 * 그래서 초 단위(1·2·4)다. 여기는 스케줄러가 돌리므로 아무것도 막지 않는다 —
 * <b>제약은 "PG 를 두드리지 않는 것"과 "너무 늦게 낫지 않는 것" 사이의 균형</b>이라 분 단위다.
 *
 * <p><b>상한을 두는 이유</b> — 상한이 없으면 24시간짜리 대기가 생긴다. 그 사이 PG 가 복구돼도
 * 우리는 모르고, 사용자는 그동안 "환불했다는데 돈이 안 들어왔다" 를 겪는다.
 * 30분이면 사람이 알람을 보고 개입하기까지의 시간과 같은 눈금이다.
 */
public final class RefundRetryPolicy {

    /** 최초 유예. 정상 환불은 PG 왕복 한 번이라 초 단위로 끝나므로, 진행 중인 건을 옆에서 다시 부르지 않을 만큼만 둔다. */
    private static final Duration INITIAL = Duration.ofMinutes(2);
    private static final Duration MAX = Duration.ofMinutes(30);
    private static final int MULTIPLIER = 2;

    private RefundRetryPolicy() {
    }

    /**
     * {@code attempts} 번 시도한 뒤 다음까지 기다릴 시간.
     *
     * <p>2분 → 4분 → 8분 → 16분 → 30분(상한). 시도 횟수가 커져도 {@code long} 이 넘치지 않도록
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
