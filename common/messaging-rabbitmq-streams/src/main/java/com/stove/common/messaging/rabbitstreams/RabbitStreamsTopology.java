package com.stove.common.messaging.rabbitstreams;

import com.rabbitmq.stream.Constants;
import com.rabbitmq.stream.Environment;
import com.rabbitmq.stream.StreamException;
import java.time.Duration;

/**
 * 토픽 하나를 RabbitMQ Super Stream 으로 옮긴 모양(결정 211).
 *
 * <ul>
 *   <li>토픽마다 super stream 하나(이름 = 토픽), 분할 {@code partitions} 개({@code <토픽>-0} …). 분할은 디스크 로그라 소비해도 지워지지 않고
 *       보존 기간({@code maxAge})이 지나야 지워진다 — 받는 쪽이 늦게 붙어도 지난 메시지를 읽는다(classic queue 와 다른 점).</li>
 *   <li>소비 그룹 = 소비자 이름. 그룹마다 큐를 따로 만들지 않고, 그룹마다 분할별 위치(offset)를 브로커에 저장한다.</li>
 *   <li>발행자는 순서 키의 {@link #hash} 로 분할을 고른다(클라이언트가 {@code Integer.remainderUnsigned(hash, 분할 수)} 번째 분할에
 *       보낸다) — 같은 키는 늘 같은 분할로 간다. classic queue·Redis Streams adapter 와 같은 {@code String.hashCode} 다.</li>
 *   <li>분할마다 단일 활성 소비자: 같은 이름의 소비자가 여럿이면 브로커가 분할을 인스턴스에 나눠 주고, 분할마다 한 소비자만 받는다.</li>
 * </ul>
 * 발행자와 소비자가 같은 분할 수를 써야 한다. 먼저 만든 쪽의 분할 수가 남는다.
 */
public final class RabbitStreamsTopology {

    /** 순서 키를 싣는 application property. 분할은 이 값의 해시로 고른다. */
    public static final String KEY_PROPERTY = "x-stove-key";

    private RabbitStreamsTopology() {
    }

    /** 분할을 고르는 해시. 클라이언트 기본(murmur3)은 패키지 밖에서 계산할 수 없어 명시한다. */
    public static int hash(String routingKey) {
        return routingKey.hashCode();
    }

    /** 이 키가 가는 분할 번호({@code <토픽>-<번호>}). 클라이언트의 해시 라우팅과 같은 계산이다. */
    public static int partition(String key, int partitions) {
        return Integer.remainderUnsigned(hash(routingKey(key)), partitions);
    }

    /** 순서 키가 없으면 빈 문자열로 라우팅한다(늘 같은 분할). */
    public static String routingKey(String key) {
        return key == null ? "" : key;
    }

    /** 없으면 만들고, 있으면 그대로 둔다. 여러 앱·인스턴스가 동시에 불러도 된다. */
    public static void declare(Environment environment, String topic, int partitions, Duration maxAge) {
        try {
            environment.streamCreator().name(topic).superStream().partitions(partitions).creator().maxAge(maxAge).create();
        } catch (StreamException exists) {
            if (exists.getCode() != Constants.RESPONSE_CODE_STREAM_ALREADY_EXISTS) {
                throw exists;
            }
        }
    }
}
