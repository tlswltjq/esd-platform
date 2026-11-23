package com.stove.common.messaging.rabbitstreams.config;

import java.time.Duration;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * RabbitMQ Streams adapter(결정 211) 설정. {@code stove.messaging.broker=rabbitmq-streams} 일 때만 쓴다.
 *
 * @param host stream 프로토콜(기본 5552) 주소. 단일 노드 기준이라 브로커가 알려 주는 주소 대신 늘 이 주소로 붙는다
 * @param port stream 프로토콜 포트
 * @param username 사용자
 * @param password 비밀번호
 * @param partitionsPerTopic 토픽 super stream 의 분할 수. 키 해시로 하나를 고른다 — Kafka 파티션 수에 해당한다
 * @param maxAge 분할 로그 보존 기간. 지나면 브로커가 지운다(Kafka {@code retention.ms} 에 해당)
 * @param alsoKafkaTopics 이 adapter 로 받을 수 없는 소비자(DB 없는 Store)를 위해 Kafka 로도 보내는 토픽
 */
@ConfigurationProperties(prefix = "stove.messaging.rabbitmq-streams")
public record RabbitStreamsMessagingProperties(String host, Integer port, String username, String password,
                                               Integer partitionsPerTopic, Duration maxAge, Set<String> alsoKafkaTopics) {

    public RabbitStreamsMessagingProperties {
        host = host == null ? "localhost" : host;
        port = port == null ? 5552 : port;
        username = username == null ? "guest" : username;
        password = password == null ? "guest" : password;
        partitionsPerTopic = partitionsPerTopic == null ? 3 : partitionsPerTopic;
        maxAge = maxAge == null ? Duration.ofDays(7) : maxAge;
        alsoKafkaTopics = alsoKafkaTopics == null ? Set.of() : Set.copyOf(alsoKafkaTopics);
        if (partitionsPerTopic < 1) {
            throw new IllegalArgumentException(
                    "stove.messaging.rabbitmq-streams.partitions-per-topic 은 1 이상이다: " + partitionsPerTopic);
        }
        if (maxAge.isNegative() || maxAge.isZero()) {
            throw new IllegalArgumentException("stove.messaging.rabbitmq-streams.max-age 는 0보다 길다: " + maxAge);
        }
    }
}
