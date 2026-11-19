package com.stove.common.messaging.redis;

import com.stove.common.event.MessagePublisher;
import com.stove.common.event.OutboundMessage;
import io.lettuce.core.api.StatefulRedisConnection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 발행 포트의 Redis Streams adapter(결정 203).
 *
 * <p>흐름: 순서 키 해시로 스트림 {@code <토픽>.<i>} 를 고른다 → {@code XADD} 로 한 항목을 붙인다(필드: 순서 키·본문·계약 헤더) →
 * Redis 가 항목 id 를 돌려주면 성공. 릴레이는 그때 행을 {@code SENT} 로 바꾼다. 연결 실패면 실패한 Future 라 다시 보낸다.
 *
 * <p>스트림은 로그처럼 남는다(소비 그룹이 나중에 생겨도 처음부터 읽는다). 소비자가 없는 토픽도 그대로 쌓인다 — Kafka 와 같다.
 * 길이 제한(MAXLEN)은 두지 않았다: 비교 실험 범위이며, 운영이면 보관 기간에 맞춰 잘라야 한다.
 */
public class RedisStreamsPublisher implements MessagePublisher {

    private final StatefulRedisConnection<String, String> connection;
    private final int streamsPerTopic;

    public RedisStreamsPublisher(StatefulRedisConnection<String, String> connection, int streamsPerTopic) {
        this.connection = connection;
        this.streamsPerTopic = streamsPerTopic;
    }

    @Override
    public CompletableFuture<Void> publish(OutboundMessage message) {
        try {
            String stream = RedisStreamTopology.stream(message.topic(), RedisStreamTopology.shard(message.key(), streamsPerTopic));
            return connection.async().xadd(stream, toFields(message)).toCompletableFuture().thenApply(id -> null);
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    static Map<String, String> toFields(OutboundMessage message) {
        Map<String, String> fields = new LinkedHashMap<>();
        if (message.key() != null) {
            fields.put(RedisStreamTopology.KEY_FIELD, message.key());
        }
        fields.put(RedisStreamTopology.PAYLOAD_FIELD, message.payload());
        for (Map.Entry<String, String> header : message.headers().entrySet()) {
            fields.put(RedisStreamTopology.HEADER_PREFIX + header.getKey(), header.getValue());
        }
        return fields;
    }
}
