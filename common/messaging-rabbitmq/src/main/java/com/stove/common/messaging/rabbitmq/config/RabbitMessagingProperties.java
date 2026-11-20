package com.stove.common.messaging.rabbitmq.config;

import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * RabbitMQ adapter(결정 203) 설정. {@code stove.messaging.broker=rabbitmq} 일 때만 쓴다.
 *
 * @param queuesPerTopic (소비 그룹, 토픽)마다 둘 큐 수. 키 해시로 하나를 고른다 — Kafka 파티션 수에 해당한다
 * @param prefetch 큐마다 소비자가 미리 받아 둘 메시지 수. 큐마다 소비자는 하나라 순서에는 영향이 없다
 * @param alsoKafkaTopics 이 adapter 로 받을 수 없는 소비자(DB 없는 Store)를 위해 Kafka 로도 보내는 토픽
 * @param optionalTopics 이 adapter 로 받는 소비자가 없는 토픽. 받을 큐가 없어 되돌아와도 성공으로 친다(그 밖의 토픽은 실패로 다시 보낸다)
 */
@ConfigurationProperties(prefix = "stove.messaging.rabbitmq")
public record RabbitMessagingProperties(Integer queuesPerTopic, Integer prefetch, Set<String> alsoKafkaTopics,
                                        Set<String> optionalTopics) {

    public RabbitMessagingProperties {
        queuesPerTopic = queuesPerTopic == null ? 3 : queuesPerTopic;
        prefetch = prefetch == null ? 10 : prefetch;
        alsoKafkaTopics = alsoKafkaTopics == null ? Set.of() : Set.copyOf(alsoKafkaTopics);
        optionalTopics = optionalTopics == null ? Set.of() : Set.copyOf(optionalTopics);
        if (queuesPerTopic < 1) {
            throw new IllegalArgumentException("stove.messaging.rabbitmq.queues-per-topic 은 1 이상이다: " + queuesPerTopic);
        }
    }
}
