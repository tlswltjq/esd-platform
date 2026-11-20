package com.stove.common.messaging.redis;

import com.stove.common.event.InboundMessage;
import com.stove.common.messaging.delivery.InPlaceDelivery;
import com.stove.common.messaging.delivery.SubscribedHandler;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;

/**
 * 수신 포트의 Redis Streams adapter(결정 203).
 *
 * <p>흐름: (소비 그룹, 토픽, 스트림 번호)마다 읽기 스레드·연결 하나.
 * <ol>
 *   <li>잠금 키({@code SET NX PX}, 기한 {@link #LEASE})를 얻은 인스턴스만 읽는다. 처음에 consumer group 을 만든다(위치 {@code 0} = 처음부터).</li>
 *   <li>그룹 읽기({@code XREADGROUP})는 Lua 한 번에 "잠금이 내 것인가 확인 + 읽기"로 한다 — 점유를 잃은 스레드가 항목을 가져가
 *       그룹 위치·미확인 목록을 바꾸지 못하게(PR 리뷰 R2). 미확인 항목(id {@code 0})이 있으면 그것부터, 없으면 새 항목({@code >}).</li>
 *   <li>새 항목이 없으면 그룹 상태를 바꾸지 않는 {@code XREAD BLOCK} 으로 스트림에 새 항목이 붙기를 기다린다(오면 바로 깬다).</li>
 *   <li>한 항목씩 {@link InPlaceDelivery#deliverHolding} 에 넘기고 끝나면 {@code XACK}.</li>
 * </ol>
 *
 * <p>점유 유지(R1): 별도 스레드가 기한({@link #LEASE})의 1/3 마다 이 인스턴스가 가진 잠금을 모두 연장한다 — 처리가 길어도 점유가 풀리지 않는다.
 * 연장에 실패하면(Redis 가 기한 안에 닿지 않음 등) 그 잠금을 잃은 것으로 표시한다. 읽기 스레드는 그와 별개로 항목마다 처리 직전에
 * Redis 에서 점유를 확인(하며 연장)하고, {@code XACK} 도 Lua 로 점유를 확인한 뒤에만 한다 — 잃었으면 처리도 확인도 하지 않는다(D-058).
 * 그 항목은 새 점유자가 미확인 목록에서 다시 처리하고, 업무는 Inbox 로 한 번만 반영된다.
 * 종료(R3): 읽기 스레드가 실제로 끝난 잠금만 놓는다. 끝나지 않은 스레드의 잠금은 연장을 멈춰 기한이 지나야 풀린다.
 */
@Slf4j
public class RedisStreamsSubscriptions implements SmartLifecycle {

    static final Duration LEASE = Duration.ofSeconds(60);
    private static final String RENEW = "if redis.call('get', KEYS[1]) == ARGV[1] then"
            + " return redis.call('pexpire', KEYS[1], ARGV[2]) else return 0 end";
    private static final String RELEASE = "if redis.call('get', KEYS[1]) == ARGV[1] then"
            + " return redis.call('del', KEYS[1]) else return 0 end";
    /** KEYS[1]=잠금, KEYS[2]=스트림, ARGV = 내 id, 그룹, consumer, 건수, 시작 id. 잠금이 내 것이 아니면 'LOST'. */
    private static final String FENCED_READ = "if redis.call('get', KEYS[1]) ~= ARGV[1] then return 'LOST' end"
            + " local r = redis.call('XREADGROUP', 'GROUP', ARGV[2], ARGV[3], 'COUNT', ARGV[4], 'STREAMS', KEYS[2], ARGV[5])"
            + " if not r then return {} end return r[1][2]";
    /** KEYS[1]=잠금, KEYS[2]=스트림, ARGV = 내 id, 그룹, 항목 id. 잠금이 내 것일 때만 확인(XACK)한다. 아니면 -1. */
    private static final String FENCED_ACK = "if redis.call('get', KEYS[1]) ~= ARGV[1] then return -1 end"
            + " return redis.call('XACK', KEYS[2], ARGV[2], ARGV[3])";

    private final Supplier<List<SubscribedHandler>> handlers;
    private final RedisClient client;
    private final InPlaceDelivery delivery;
    private final int streamsPerTopic;
    private final int batchSize;
    private final long blockMs;
    private final Duration holdStart;
    private final Duration lease;
    private final String instanceId = UUID.randomUUID().toString();
    /** 지금 이 인스턴스가 가진 잠금. 연장 스레드가 잃으면 지운다. */
    private final Set<String> held = ConcurrentHashMap.newKeySet();
    /** 읽기 스레드가 끝난 잠금(종료 때 놓아도 되는 것). */
    private final Set<String> finished = ConcurrentHashMap.newKeySet();
    private final List<String> locks = new ArrayList<>();
    private final List<StatefulRedisConnection<String, String>> connections = new ArrayList<>();
    private StatefulRedisConnection<String, String> leaseConnection;
    private ExecutorService readers;
    private ScheduledExecutorService renewer;
    private volatile boolean running;
    private final AtomicInteger lostReads = new AtomicInteger();
    private volatile Consumer<String> afterAck = id -> {
    };

    public RedisStreamsSubscriptions(Supplier<List<SubscribedHandler>> handlers, RedisClient client, InPlaceDelivery delivery,
                                     int streamsPerTopic, int batchSize, long blockMs) {
        this(handlers, client, delivery, streamsPerTopic, batchSize, blockMs, InPlaceDelivery.HOLD_START, LEASE);
    }

    RedisStreamsSubscriptions(Supplier<List<SubscribedHandler>> handlers, RedisClient client, InPlaceDelivery delivery,
                              int streamsPerTopic, int batchSize, long blockMs, Duration holdStart, Duration lease) {
        this.lease = lease;
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
        leaseConnection = client.connect();
        readers = Executors.newFixedThreadPool(Math.max(1, unique.size() * streamsPerTopic));
        renewer = Executors.newSingleThreadScheduledExecutor();
        running = true;
        renewer.scheduleWithFixedDelay(this::renewHeld, lease.toMillis() / 3, lease.toMillis() / 3, TimeUnit.MILLISECONDS);
        for (SubscribedHandler subscription : unique.values()) {
            for (int shard = 0; shard < streamsPerTopic; shard++) {
                StatefulRedisConnection<String, String> connection = client.connect();
                connections.add(connection);
                String stream = RedisStreamTopology.stream(subscription.topic(), shard);
                String lock = RedisStreamTopology.lockKey(subscription.group(), stream);
                locks.add(lock);
                readers.submit(() -> {
                    try {
                        readLoop(subscription, stream, lock, connection.sync());
                    } finally {
                        finished.add(lock);
                    }
                });
            }
        }
        log.info("Redis Streams 구독 {}개 시작 (구독마다 스트림 {}개)", unique.size(), streamsPerTopic);
    }

    private void readLoop(SubscribedHandler subscription, String stream, String lock, RedisCommands<String, String> redis) {
        boolean groupReady = false;
        String lastSeen = "$";
        while (running) {
            try {
                if (!held.contains(lock) && !acquire(redis, lock)) {
                    Thread.sleep(1000);
                    continue;
                }
                if (!groupReady) {
                    createGroup(redis, subscription.group(), stream);
                    groupReady = true;
                }
                // 미확인 항목(앞 점유자가 처리하다 만 것)부터. 없으면 새 항목.
                List<StreamMessage<String, String>> entries = fencedRead(redis, lock, subscription.group(), stream, "0");
                if (entries != null && entries.isEmpty()) {
                    entries = fencedRead(redis, lock, subscription.group(), stream, ">");
                }
                if (entries == null) {
                    held.remove(lock);
                    lostReads.incrementAndGet();
                    continue;
                }
                if (entries.isEmpty()) {
                    lastSeen = awaitNew(redis, stream, lastSeen);
                    continue;
                }
                for (StreamMessage<String, String> entry : entries) {
                    // 항목마다 Redis 에서 점유를 확인(하며 연장)한다. 연장 스레드의 주기(기한의 1/3)를 기다리면
                    // 그 사이 같이 읽어 둔 뒤 항목을 새 점유자와 동시에 처리할 수 있다(D-058, PR #215 R3).
                    if (!running || !renew(redis, lock)) {
                        held.remove(lock);
                        break;
                    }
                    if (entry.getBody() != null && !entry.getBody().isEmpty()) {
                        // 재시도·보관 대기 중에도 매번 Redis 에서 점유를 확인한다(PR #215 재리뷰 R1).
                        delivery.deliverHolding(subscription.group(), subscription.handler(), subscription.noRetryFor(),
                                toInbound(subscription.topic(), stream, entry), () -> running && stillMine(redis, lock), holdStart);
                    }
                    if (!fencedAck(redis, lock, subscription.group(), stream, entry.getId())) {
                        // 처리하는 사이 점유를 잃었다. 확인하지 않고 둔다 — 새 점유자가 미확인 목록에서 다시 처리한다.
                        held.remove(lock);
                        break;
                    }
                    afterAck.accept(entry.getId());
                    lastSeen = entry.getId();
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

    /** @return 읽은 항목(없으면 빈 목록), 잠금이 내 것이 아니면 null */
    @SuppressWarnings("unchecked")
    private List<StreamMessage<String, String>> fencedRead(RedisCommands<String, String> redis, String lock, String group,
                                                           String stream, String from) {
        Object reply = redis.eval(FENCED_READ, ScriptOutputType.MULTI, new String[]{lock, stream},
                instanceId, group, RedisStreamTopology.CONSUMER, Integer.toString(batchSize), from);
        if ("LOST".equals(reply) || reply instanceof List<?> list && list.size() == 1 && "LOST".equals(list.get(0))) {
            return null;
        }
        List<StreamMessage<String, String>> entries = new ArrayList<>();
        if (!(reply instanceof List<?> rows)) {
            return entries;
        }
        for (Object row : rows) {
            List<Object> pair = (List<Object>) row;
            String id = (String) pair.get(0);
            Map<String, String> body = new LinkedHashMap<>();
            if (pair.size() > 1 && pair.get(1) instanceof List<?> fields) {
                for (int i = 0; i + 1 < fields.size(); i += 2) {
                    body.put((String) fields.get(i), (String) fields.get(i + 1));
                }
            }
            entries.add(new StreamMessage<>(stream, id, body));
        }
        return entries;
    }

    /**
     * 그룹 상태를 바꾸지 않고 스트림에 {@code lastSeen} 뒤 항목이 붙기를 최대 blockMs 기다린다.
     * @return 다음에 기다릴 기준 id(본 것 가운데 가장 뒤)
     */
    private String awaitNew(RedisCommands<String, String> redis, String stream, String lastSeen) {
        List<StreamMessage<String, String>> seen = redis.xread(XReadArgs.Builder.count(1).block(blockMs),
                XReadArgs.StreamOffset.from(stream, lastSeen));
        return seen.isEmpty() ? lastSeen : seen.get(seen.size() - 1).getId();
    }

    /** 잠금이 내 것일 때만 {@code XACK}. @return 확인했으면 true, 점유를 잃었으면 false */
    private boolean fencedAck(RedisCommands<String, String> redis, String lock, String group, String stream, String id) {
        Long acked = redis.eval(FENCED_ACK, ScriptOutputType.INTEGER, new String[]{lock, stream}, instanceId, group, id);
        return acked != null && acked >= 0;
    }

    /** 잠금이 내 것이면 연장하고 true. 아니면 잃은 것으로 표시하고 false. */
    private boolean stillMine(RedisCommands<String, String> redis, String lock) {
        if (renew(redis, lock)) {
            return true;
        }
        held.remove(lock);
        return false;
    }

    /** 테스트 관측용: 점유를 잃어 그룹 읽기가 거절된 횟수. */
    int lostReads() {
        return lostReads.get();
    }

    /** 테스트 관측용: 항목을 확인(XACK)한 직후 그 id 로 부른다. */
    void onAcknowledged(Consumer<String> listener) {
        this.afterAck = listener;
    }

    /** 잠금이 내 것이면 기한을 연장하고 true. */
    private boolean renew(RedisCommands<String, String> redis, String lock) {
        Long renewed = redis.eval(RENEW, ScriptOutputType.INTEGER, new String[]{lock}, instanceId, Long.toString(lease.toMillis()));
        return renewed != null && renewed == 1L;
    }

    private boolean acquire(RedisCommands<String, String> redis, String lock) {
        if ("OK".equals(redis.set(lock, instanceId, SetArgs.Builder.nx().px(lease.toMillis())))) {
            held.add(lock);
            return true;
        }
        return false;
    }

    /** 가진 잠금을 모두 연장한다. 연장되지 않으면 잃은 것으로 둔다. */
    private void renewHeld() {
        for (String lock : held) {
            try {
                Long renewed = leaseConnection.sync().eval(RENEW, ScriptOutputType.INTEGER, new String[]{lock}, instanceId,
                        Long.toString(lease.toMillis()));
                if (renewed == null || renewed != 1L) {
                    held.remove(lock);
                    log.warn("점유를 잃었다, 이 스트림의 처리를 멈춘다 lock={}", lock);
                }
            } catch (RuntimeException failure) {
                // 닿지 않으면 다음 주기에 다시. 기한 안에 못 닿으면 다른 인스턴스가 이어받고, 다음 연장이 0 을 받아 여기서 놓는다.
                log.warn("점유 연장 실패 lock={}", lock, failure);
            }
        }
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
                if (!readers.awaitTermination(blockMs + 10_000, TimeUnit.MILLISECONDS)) {
                    readers.shutdownNow();
                    readers.awaitTermination(5, TimeUnit.SECONDS);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        if (renewer != null) {
            renewer.shutdownNow();
        }
        // 읽기 스레드가 끝난 잠금만 놓는다. 끝나지 않은(처리 중인) 스레드의 잠금은 연장을 멈췄으니 기한이 지나야 풀린다.
        if (leaseConnection != null) {
            for (String lock : locks) {
                if (!finished.contains(lock)) {
                    log.warn("처리가 끝나지 않아 점유를 놓지 않는다(기한이 지나면 풀린다) lock={}", lock);
                    continue;
                }
                try {
                    leaseConnection.sync().eval(RELEASE, ScriptOutputType.INTEGER, new String[]{lock}, instanceId);
                } catch (RuntimeException failure) {
                    log.warn("점유 해제 실패, 기한이 지나면 다른 인스턴스가 이어받는다 lock={}", lock, failure);
                }
            }
            leaseConnection.close();
        }
        for (StatefulRedisConnection<String, String> connection : connections) {
            connection.close();
        }
        connections.clear();
        locks.clear();
        held.clear();
        finished.clear();
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
