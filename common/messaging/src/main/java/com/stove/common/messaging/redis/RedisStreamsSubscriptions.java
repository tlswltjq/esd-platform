package com.stove.common.messaging.redis;

import com.stove.common.event.InboundMessage;
import com.stove.common.messaging.delivery.InPlaceDelivery;
import com.stove.common.messaging.delivery.SubscribedHandler;
import io.lettuce.core.Consumer;
import io.lettuce.core.RedisBusyException;
import io.lettuce.core.RedisClient;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.SetArgs;
import io.lettuce.core.StreamMessage;
import io.lettuce.core.XGroupCreateArgs;
import io.lettuce.core.XReadArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;

/**
 * 수신 포트의 Redis Streams adapter(결정 203).
 *
 * <p>흐름: (소비 그룹, 토픽, 스트림 번호)마다 스레드 하나와 연결 하나 →
 * 잠금 키를 얻은 인스턴스만 읽는다({@code SET NX PX}, 기한 {@link #LEASE}, 항목마다 연장) →
 * 처음에 consumer group 을 만든다(없으면 스트림도, 위치 {@code 0} = 처음부터) →
 * 점유를 얻으면 먼저 미확인 항목(앞 점유자가 처리하다 만 것, id {@code 0})부터, 그다음 새 항목({@code >})을 {@code XREADGROUP} 으로 읽는다 →
 * 한 항목씩 {@link InPlaceDelivery#deliverHolding} 에 넘기고 끝나면 {@code XACK}.
 *
 * <p>순서(결정 202): 같은 키는 같은 스트림에 쌓이고, 스트림 하나는 한 인스턴스의 한 스레드가 차례로 처리한다.
 * 점유를 잃으면(연장 실패) 그 항목을 처리하지 않고 다시 점유를 기다린다. 처리 중에 기한이 지나 다른 인스턴스가 같은 항목을 처리해도
 * 업무는 Inbox 로 한 번만 반영된다.
 */
@Slf4j
public class RedisStreamsSubscriptions implements SmartLifecycle {

    static final Duration LEASE = Duration.ofSeconds(60);
    private static final String RENEW = "if redis.call('get', KEYS[1]) == ARGV[1] then"
            + " return redis.call('pexpire', KEYS[1], ARGV[2]) else return 0 end";
    private static final String RELEASE = "if redis.call('get', KEYS[1]) == ARGV[1] then"
            + " return redis.call('del', KEYS[1]) else return 0 end";

    private final Supplier<List<SubscribedHandler>> handlers;
    private final RedisClient client;
    private final InPlaceDelivery delivery;
    private final int streamsPerTopic;
    private final int batchSize;
    private final long blockMs;
    private final Duration holdStart;
    private final String instanceId = UUID.randomUUID().toString();
    private final List<StatefulRedisConnection<String, String>> connections = new ArrayList<>();
    private final List<String> locks = new ArrayList<>();
    private ExecutorService readers;
    private volatile boolean running;

    public RedisStreamsSubscriptions(Supplier<List<SubscribedHandler>> handlers, RedisClient client, InPlaceDelivery delivery,
                                     int streamsPerTopic, int batchSize, long blockMs) {
        this(handlers, client, delivery, streamsPerTopic, batchSize, blockMs, InPlaceDelivery.HOLD_START);
    }

    RedisStreamsSubscriptions(Supplier<List<SubscribedHandler>> handlers, RedisClient client, InPlaceDelivery delivery,
                              int streamsPerTopic, int batchSize, long blockMs, Duration holdStart) {
        this.handlers = handlers;
        this.client = client;
        this.delivery = delivery;
        this.streamsPerTopic = streamsPerTopic;
        this.batchSize = batchSize;
        this.blockMs = blockMs;
        this.holdStart = holdStart;
    }

    @Override
    public void start() {
        Map<String, SubscribedHandler> unique = new LinkedHashMap<>();
        for (SubscribedHandler handler : handlers.get()) {
            unique.put(handler.group() + "|" + handler.topic(), handler);
        }
        readers = Executors.newFixedThreadPool(Math.max(1, unique.size() * streamsPerTopic));
        running = true;
        for (SubscribedHandler subscription : unique.values()) {
            for (int shard = 0; shard < streamsPerTopic; shard++) {
                StatefulRedisConnection<String, String> connection = client.connect();
                connections.add(connection);
                String stream = RedisStreamTopology.stream(subscription.topic(), shard);
                locks.add(RedisStreamTopology.lockKey(subscription.group(), stream));
                readers.submit(() -> readLoop(subscription, stream, connection.sync()));
            }
        }
        log.info("Redis Streams 구독 {}개 시작 (구독마다 스트림 {}개)", unique.size(), streamsPerTopic);
    }

    private void readLoop(SubscribedHandler subscription, String stream, RedisCommands<String, String> redis) {
        String lock = RedisStreamTopology.lockKey(subscription.group(), stream);
        boolean groupReady = false;
        while (running) {
            try {
                if (!acquire(redis, lock)) {
                    Thread.sleep(1000);
                    continue;
                }
                if (!groupReady) {
                    createGroup(redis, subscription.group(), stream);
                    groupReady = true;
                }
                // 점유를 얻은 직후에는 미확인 항목(앞 점유자가 처리하다 만 것)부터 비운다.
                boolean owner = true;
                List<StreamMessage<String, String>> pending = read(subscription, stream, redis, false);
                while (running && owner && !pending.isEmpty()) {
                    owner = process(subscription, stream, redis, lock, pending);
                    pending = owner ? read(subscription, stream, redis, false) : List.of();
                }
                // 그다음 새 항목. 점유를 잃으면 바깥으로 나가 다시 점유부터.
                while (running && owner) {
                    owner = process(subscription, stream, redis, lock, read(subscription, stream, redis, true))
                            && renew(redis, lock);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException failure) {
                if (!running) {
                    return;
                }
                log.error("Redis Streams 읽기 실패, 잠시 뒤 이어서 읽는다 group={} stream={}", subscription.group(), stream, failure);
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /** 미확인 항목(id 0 부터) 또는 새 항목({@code >}, 최대 blockMs 기다림)을 읽는다. */
    private List<StreamMessage<String, String>> read(SubscribedHandler subscription, String stream,
                                                     RedisCommands<String, String> redis, boolean fresh) {
        XReadArgs args = fresh ? XReadArgs.Builder.count(batchSize).block(blockMs) : XReadArgs.Builder.count(batchSize);
        XReadArgs.StreamOffset<String> from = fresh
                ? XReadArgs.StreamOffset.lastConsumed(stream) : XReadArgs.StreamOffset.from(stream, "0");
        return redis.xreadgroup(Consumer.from(subscription.group(), RedisStreamTopology.CONSUMER), args, from);
    }

    /**
     * 한 항목씩 처리하고 {@code XACK}. 항목마다 점유를 연장한다.
     *
     * @return 점유가 아직 내 것이면 true. 잃었으면 남은 항목을 처리하지 않고 false
     */
    private boolean process(SubscribedHandler subscription, String stream, RedisCommands<String, String> redis, String lock,
                            List<StreamMessage<String, String>> entries) {
        for (StreamMessage<String, String> entry : entries) {
            if (!running || !renew(redis, lock)) {
                return false;
            }
            // 지워진 항목은 미확인 목록에 본문 없이 남는다. 처리할 것이 없으니 확인만 한다.
            if (entry.getBody() != null && !entry.getBody().isEmpty()) {
                delivery.deliverHolding(subscription.group(), subscription.handler(), subscription.noRetryFor(),
                        toInbound(subscription.topic(), stream, entry), () -> running, holdStart);
            }
            redis.xack(stream, subscription.group(), entry.getId());
        }
        return true;
    }

    private boolean acquire(RedisCommands<String, String> redis, String lock) {
        if ("OK".equals(redis.set(lock, instanceId, SetArgs.Builder.nx().px(LEASE.toMillis())))) {
            return true;
        }
        return renew(redis, lock);
    }

    private boolean renew(RedisCommands<String, String> redis, String lock) {
        Long renewed = redis.eval(RENEW, ScriptOutputType.INTEGER, new String[]{lock}, instanceId,
                Long.toString(LEASE.toMillis()));
        return renewed != null && renewed == 1L;
    }

    private static void createGroup(RedisCommands<String, String> redis, String group, String stream) {
        try {
            redis.xgroupCreate(XReadArgs.StreamOffset.from(stream, "0"), group, XGroupCreateArgs.Builder.mkstream());
        } catch (RedisBusyException alreadyExists) {
            // BUSYGROUP: 이미 있다 — 위치는 Redis 가 기억한다.
        }
    }

    /** 발행 adapter 가 실은 필드를 그대로 되살린다. */
    static InboundMessage toInbound(String topic, String stream, StreamMessage<String, String> entry) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        for (Map.Entry<String, String> field : entry.getBody().entrySet()) {
            if (field.getKey().startsWith(RedisStreamTopology.HEADER_PREFIX)) {
                headers.put(field.getKey().substring(RedisStreamTopology.HEADER_PREFIX.length()), List.of(field.getValue()));
            }
        }
        return new InboundMessage(topic, entry.getBody().get(RedisStreamTopology.KEY_FIELD),
                entry.getBody().get(RedisStreamTopology.PAYLOAD_FIELD), headers, stream + " id=" + entry.getId());
    }

    @Override
    public void stop() {
        running = false;
        if (readers != null) {
            readers.shutdown();
            try {
                if (!readers.awaitTermination(blockMs + 5000, TimeUnit.MILLISECONDS)) {
                    readers.shutdownNow();
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                readers.shutdownNow();
            }
        }
        // 점유를 놓아 다른 인스턴스가 기한을 기다리지 않고 이어받게 한다.
        if (!connections.isEmpty()) {
            try {
                RedisCommands<String, String> redis = connections.get(0).sync();
                for (String lock : locks) {
                    redis.eval(RELEASE, ScriptOutputType.INTEGER, new String[]{lock}, instanceId);
                }
            } catch (RuntimeException failure) {
                log.warn("점유 해제 실패, 기한이 지나면 다른 인스턴스가 이어받는다", failure);
            }
        }
        for (StatefulRedisConnection<String, String> connection : connections) {
            connection.close();
        }
        connections.clear();
        locks.clear();
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
