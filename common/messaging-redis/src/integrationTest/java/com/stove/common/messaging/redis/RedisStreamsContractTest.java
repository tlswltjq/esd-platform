package com.stove.common.messaging.redis;

import com.stove.common.event.EventHandler;
import com.stove.common.event.KeyQuarantine;
import com.stove.common.event.OutboundMessage;
import com.stove.common.messaging.contract.MessagingContractTest;
import com.stove.common.messaging.delivery.InPlaceDelivery;
import com.stove.common.messaging.delivery.SubscribedHandler;
import com.stove.common.messaging.trace.ReplayTracing;
import com.stove.common.testcontainers.InfraContainers;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.containers.ContainerState;
import org.testcontainers.containers.GenericContainer;

/** Redis Streams adapter 가 공통 계약(결정 202·206)을 지키는지 실제 Redis·MySQL 로 본다(결정 209). */
@SpringBootTest(properties = "stove.outbox.relay-enabled=false")
@Import({InfraContainers.MySql.class, InfraContainers.Redis.class})
class RedisStreamsContractTest extends MessagingContractTest {

    /** 평소 3, S4 측정 때만 6(결정 218, {@link MessagingContractTest#partitions()}). */
    private static final int STREAMS = partitions();
    private static final List<Duration> SHORT_RETRIES = List.of(Duration.ofMillis(10), Duration.ofMillis(10), Duration.ofMillis(10));

    @Autowired
    GenericContainer<?> redisContainer;

    private RedisClient client;
    private StatefulRedisConnection<String, String> connection;
    private RedisStreamsPublisher publisher;

    @BeforeEach
    void connect() {
        client = RedisClient.create("redis://" + redisContainer.getHost() + ":" + redisContainer.getMappedPort(6379));
        connection = client.connect();
        publisher = new RedisStreamsPublisher(connection, STREAMS);
    }

    /** S3(결정 222): 공유 Redis 컨테이너. 측정 때 {@code SharedContainers} 가 메모리 1GB·maxmemory 512mb·noeviction·AOF 를 건다. */
    @Override
    protected Optional<ContainerState> brokerContainer() {
        return Optional.of(redisContainer);
    }

    @Override
    protected String brokerDataDir() {
        return "/data";
    }

    /** 한도가 실제로 걸렸는지 결과에 남기려고 쌓기 직전에 서버 설정을 읽는다. */
    @Override
    protected Map<String, String> brokerSettings() {
        Map<String, String> settings = new LinkedHashMap<>();
        for (String name : List.of("maxmemory", "maxmemory-policy", "appendonly", "appendfsync")) {
            Map<String, String> value = connection.sync().configGet(name);
            settings.put(name, value.get(name));
        }
        return settings;
    }

    /**
     * 재개 뒤 메모리(PR #242 R1). {@code noeviction} 으로 꽉 차면 잠금 {@code SET}·{@code XREADGROUP} 같은 쓰기 명령도 OOM 으로
     * 거절되어 재개 소비자가 진척하지 못한다. 그것이 유실인지 재개 실패인지 결과에서 가르려고 used_memory·maxmemory 를 남긴다.
     */
    @Override
    protected Map<String, String> brokerStateAfterResume() {
        Map<String, String> state = new LinkedHashMap<>();
        String info = connection.sync().info("memory");
        for (String line : info.split("\r?\n")) {
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String name = line.substring(0, colon);
            if (name.equals("used_memory") || name.equals("maxmemory") || name.equals("maxmemory_policy")) {
                state.put(name, line.substring(colon + 1).trim());
            }
        }
        return state;
    }

    @Override
    protected void closeAdapter() {
        connection.close();
        client.shutdown();
    }

    @Override
    protected AutoCloseable subscribe(String group, String topic, KeyQuarantine storage, EventHandler handler,
                                      List<Class<? extends RuntimeException>> noRetryFor) {
        RedisStreamsSubscriptions subscriptions = new RedisStreamsSubscriptions(
                () -> List.of(new SubscribedHandler(group, topic, handler, asArray(noRetryFor))), client,
                new InPlaceDelivery(storage, ReplayTracing.NONE, retryDelays(SHORT_RETRIES)),
                STREAMS, 10, 200, Duration.ofMillis(20), Duration.ofSeconds(60));
        subscriptions.start();
        return subscriptions::stop;
    }

    @Override
    protected Optional<String> keyInSamePartition(String key) {
        return keyWhere(candidate -> RedisStreamTopology.shard(candidate, STREAMS) == RedisStreamTopology.shard(key, STREAMS));
    }

    @Override
    protected Optional<String> keyInOtherPartition(String key) {
        return keyWhere(candidate -> RedisStreamTopology.shard(candidate, STREAMS) != RedisStreamTopology.shard(key, STREAMS));
    }

    @Override
    protected void publish(OutboundMessage message) {
        publisher.publish(message).orTimeout(10, TimeUnit.SECONDS).join();
    }
}
