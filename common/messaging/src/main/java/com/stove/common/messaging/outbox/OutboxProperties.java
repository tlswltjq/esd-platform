package com.stove.common.messaging.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** stove.outbox.* 설정 */
@ConfigurationProperties(prefix = "stove.outbox")
public record OutboxProperties(
        Boolean relayEnabled,
        Integer batchSize,
        Long pollIntervalMs,
        Integer maxRetry,
        Integer maxBatchesPerCycle
) {
    public OutboxProperties {
        relayEnabled = relayEnabled == null || relayEnabled;
        batchSize = batchSize == null ? 200 : batchSize;
        // 스케줄러는 이 값을 읽지 않는다 — @Scheduled 가 플레이스홀더로 직접 바인딩하므로
        // 실제로 주기를 정하는 기본값은 OutboxRelay#relay 쪽이다. 여기를 고쳐도 주기는 안 바뀐다.
        pollIntervalMs = pollIntervalMs == null ? 200L : pollIntervalMs;
        maxRetry = maxRetry == null ? 10 : maxRetry;
        // 한 회차가 스케줄러 스레드를 무한정 붙들지 않도록 상한을 둔다.
        // 적체가 이보다 크면 다음 폴링에서 이어서 비운다.
        maxBatchesPerCycle = maxBatchesPerCycle == null ? 10 : maxBatchesPerCycle;
    }
}
