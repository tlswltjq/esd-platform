package com.stove.common.messaging.kafka;

import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * 컨슈머 재시도 정책의 단일 출처.
 *
 * <p>정책을 명시하지 않으면 스프링 카프카 기본값인 {@code FixedBackOff(0ms, 9회)} 가 쓰인다 —
 * 총 10회가 수 밀리초 만에 소진되므로 커넥션풀 순간 고갈처럼 흔한 장애도 넘기지 못한다.
 * "재시도가 있다"와 "재시도가 쓸모 있다"는 다르다.
 *
 * <p>블로킹 재시도라 대기하는 동안 해당 파티션이 멈춘다. 총 대기가
 * {@code max.poll.interval.ms}(기본 5분)를 넘으면 컨슈머가 그룹에서 쫓겨나므로
 * 여유를 크게 두고 잡았다 — 1 + 2 + 4 = 7초.
 */
public final class ConsumerRetryPolicy {

    /** 최초 배달 1회 + 재시도 3회 = 총 4회 시도 */
    public static final int MAX_RETRIES = 3;
    private static final long INITIAL_INTERVAL_MS = 1_000L;
    private static final long MAX_INTERVAL_MS = 8_000L;
    private static final double MULTIPLIER = 2.0;

    private ConsumerRetryPolicy() {
    }

    /**
     * 성공할 때까지 1·2·4·8·8…초로 재시도한다({@code EventSubscriber#retryUntilSuccess}, 결정 226). 한 번의 대기가 8초를 넘지
     * 않아 {@code max.poll.interval.ms} 안에서 오류 처리기가 다시 가져온다.
     */
    public static BackOff untilSuccess() {
        ExponentialBackOff backOff = new ExponentialBackOff(INITIAL_INTERVAL_MS, MULTIPLIER);
        backOff.setMaxInterval(MAX_INTERVAL_MS);
        return backOff;
    }

    public static BackOff backOff() {
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(MAX_RETRIES);
        backOff.setInitialInterval(INITIAL_INTERVAL_MS);
        backOff.setMultiplier(MULTIPLIER);
        backOff.setMaxInterval(MAX_INTERVAL_MS);
        return backOff;
    }
}
