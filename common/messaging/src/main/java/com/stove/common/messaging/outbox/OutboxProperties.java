package com.stove.common.messaging.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** stove.outbox.* 설정 */
@ConfigurationProperties(prefix = "stove.outbox")
public record OutboxProperties(
        Boolean relayEnabled,
        Integer batchSize,
        Long pollIntervalMs,
        Integer maxRetry
) {
    public OutboxProperties {
        relayEnabled = relayEnabled == null || relayEnabled;
        batchSize = batchSize == null ? 200 : batchSize;
        pollIntervalMs = pollIntervalMs == null ? 1000L : pollIntervalMs;
        maxRetry = maxRetry == null ? 10 : maxRetry;
    }
}
