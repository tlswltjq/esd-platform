package com.stove.common.messaging.direct.config;

import java.util.Map;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 브로커 없음 기준선(결정 203)의 설정. {@code stove.messaging.broker=direct} 일 때만 쓴다.
 *
 * @param pollIntervalMs 소비자가 발행 모듈 Outbox 를 읽는 주기(기본 200ms, 릴레이와 같다)
 * @param batchSize 한 번에 읽을 행 수(기본 100)
 * @param sources 토픽 → 그 토픽을 발행하는 모듈의 Outbox 표({@code 스키마.outbox_event}). 없으면 {@code stove.<x>.v1 → stove_<x>.outbox_event}
 * @param alsoKafkaTopics 직접 읽을 수 없는 소비자(DB 없는 Store)를 위해 Kafka 로도 보내는 토픽
 */
@ConfigurationProperties(prefix = "stove.messaging.direct")
public record DirectMessagingProperties(Long pollIntervalMs, Integer batchSize, Map<String, String> sources,
                                        Set<String> alsoKafkaTopics) {
    public DirectMessagingProperties {
        pollIntervalMs = pollIntervalMs == null ? 200L : pollIntervalMs;
        batchSize = batchSize == null ? 100 : batchSize;
        sources = sources == null ? Map.of() : Map.copyOf(sources);
        alsoKafkaTopics = alsoKafkaTopics == null ? Set.of() : Set.copyOf(alsoKafkaTopics);
    }
}
