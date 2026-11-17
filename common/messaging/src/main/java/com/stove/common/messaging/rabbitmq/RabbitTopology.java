package com.stove.common.messaging.rabbitmq;

/**
 * 토픽 하나를 RabbitMQ 로 옮긴 모양.
 *
 * <ul>
 *   <li>토픽마다 direct exchange 하나(이름 = 토픽).</li>
 *   <li>(소비 그룹, 토픽)마다 큐 {@code queuesPerTopic} 개: {@code <그룹>.<토픽>.<i>}. 큐 i 는 라우팅 키 {@code "i"} 로 묶인다.</li>
 *   <li>발행자는 순서 키의 해시로 i 를 고른다 — 같은 키는 늘 같은 큐로 간다(Kafka 의 키 → 파티션과 같은 역할).</li>
 *   <li>큐는 {@code x-single-active-consumer} 라 인스턴스가 여럿이어도 큐마다 한 소비자만 받는다 — 같은 키는 한 번에 하나, 보낸 순서대로.</li>
 * </ul>
 * 발행자와 소비자가 같은 {@code queuesPerTopic} 을 써야 한다.
 */
public final class RabbitTopology {

    /** 순서 키를 싣는 AMQP 헤더. 라우팅 키는 큐 번호라 키 원문을 따로 싣는다. */
    public static final String KEY_HEADER = "x-stove-key";

    private RabbitTopology() {
    }

    public static String exchange(String topic) {
        return topic;
    }

    public static int shard(String key, int queuesPerTopic) {
        return Math.floorMod(key == null ? 0 : key.hashCode(), queuesPerTopic);
    }

    public static String routingKey(int shard) {
        return Integer.toString(shard);
    }

    public static String queue(String group, String topic, int shard) {
        return group + "." + topic + "." + shard;
    }
}
