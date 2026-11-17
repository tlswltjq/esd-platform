package com.stove.common.messaging.config;

import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Redis Streams adapter(결정 203) 설정. {@code stove.messaging.broker=redis-streams} 일 때만 쓴다.
 *
 * @param uri Redis 주소(예: {@code redis://streams:6379})
 * @param streamsPerTopic 토픽마다 둘 스트림 수. 키 해시로 하나를 고른다 — Kafka 파티션 수에 해당한다
 * @param batchSize 한 번에 읽을 최대 건수
 * @param blockMs 새 메시지를 기다리는 최대 시간. 메시지가 오면 바로 돌아오므로 지연의 바닥이 아니다
 * @param alsoKafkaTopics 이 adapter 로 받을 수 없는 소비자(DB 없는 Store)를 위해 Kafka 로도 보내는 토픽
 */
@ConfigurationProperties(prefix = "stove.messaging.redis-streams")
public record RedisStreamsMessagingProperties(String uri, Integer streamsPerTopic, Integer batchSize, Long blockMs,
                                              Set<String> alsoKafkaTopics) {

    public RedisStreamsMessagingProperties {
        uri = uri == null || uri.isBlank() ? "redis://localhost:6379" : uri;
        streamsPerTopic = streamsPerTopic == null ? 3 : streamsPerTopic;
        batchSize = batchSize == null ? 10 : batchSize;
        blockMs = blockMs == null ? 1000L : blockMs;
        alsoKafkaTopics = alsoKafkaTopics == null ? Set.of() : Set.copyOf(alsoKafkaTopics);
        if (streamsPerTopic < 1) {
            throw new IllegalArgumentException("stove.messaging.redis-streams.streams-per-topic 은 1 이상이다: " + streamsPerTopic);
        }
    }
}
