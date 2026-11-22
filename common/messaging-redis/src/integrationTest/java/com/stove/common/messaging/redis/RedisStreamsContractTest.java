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
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.containers.GenericContainer;

/** Redis Streams adapter 가 공통 계약(결정 202·206)을 지키는지 실제 Redis·MySQL 로 본다(결정 209). */
@SpringBootTest(properties = "stove.outbox.relay-enabled=false")
@Import({InfraContainers.MySql.class, InfraContainers.Redis.class})
class RedisStreamsContractTest extends MessagingContractTest {

    private static final int STREAMS = 3;
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
                new InPlaceDelivery(storage, ReplayTracing.NONE, SHORT_RETRIES),
                STREAMS, 10, 200, Duration.ofMillis(20), Duration.ofSeconds(60));
        subscriptions.start();
        return subscriptions::stop;
    }

    @Override
    protected void publish(OutboundMessage message) {
        publisher.publish(message).orTimeout(10, TimeUnit.SECONDS).join();
    }
}
