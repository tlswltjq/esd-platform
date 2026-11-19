package com.stove.common.messaging.redis;

/**
 * 토픽 하나를 Redis Streams 로 옮긴 모양.
 *
 * <ul>
 *   <li>토픽마다 스트림 {@code streamsPerTopic} 개: {@code <토픽>.<i>}. 발행자는 순서 키 해시로 i 를 고른다(Kafka 의 키 → 파티션).</li>
 *   <li>소비 그룹마다 각 스트림에 Redis consumer group 하나(이름 = 소비 그룹). 읽은 위치·미확인(pending) 목록은 Redis 가 갖는다.</li>
 *   <li>같은 그룹의 인스턴스가 여럿이어도 스트림마다 한 곳만 읽는다: 잠금 키 {@link #lockKey} 를 가진 인스턴스.
 *       consumer 이름은 늘 {@link #CONSUMER} 하나라, 이어받은 인스턴스가 앞 점유자의 미확인 항목을 그대로 이어서 처리한다.</li>
 * </ul>
 */
public final class RedisStreamTopology {

    public static final String KEY_FIELD = "key";
    public static final String PAYLOAD_FIELD = "payload";
    /** 계약 헤더는 이 접두사를 붙인 필드로 싣는다. */
    public static final String HEADER_PREFIX = "h:";
    public static final String CONSUMER = "owner";

    private RedisStreamTopology() {
    }

    public static int shard(String key, int streamsPerTopic) {
        return Math.floorMod(key == null ? 0 : key.hashCode(), streamsPerTopic);
    }

    public static String stream(String topic, int shard) {
        return topic + "." + shard;
    }

    public static String lockKey(String group, String stream) {
        return "stove:stream-owner:" + group + ":" + stream;
    }
}
