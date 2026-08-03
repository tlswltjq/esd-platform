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
        pollIntervalMs = pollIntervalMs == null ? 1000L : pollIntervalMs;
        maxRetry = maxRetry == null ? 10 : maxRetry;
        // 한 회차가 스케줄러 스레드를 무한정 붙들지 않도록 상한을 둔다.
        // 적체가 이보다 크면 다음 폴링에서 이어서 비운다.
        maxBatchesPerCycle = maxBatchesPerCycle == null ? 10 : maxBatchesPerCycle;
    }
}
