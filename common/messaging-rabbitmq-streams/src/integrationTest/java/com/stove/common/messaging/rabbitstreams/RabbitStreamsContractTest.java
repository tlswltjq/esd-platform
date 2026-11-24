package com.stove.common.messaging.rabbitstreams;

import static org.assertj.core.api.Assertions.assertThat;

import com.rabbitmq.stream.Environment;
import com.stove.common.event.EventHandler;
import com.stove.common.event.KeyQuarantine;
import com.stove.common.event.OutboundMessage;
import com.stove.common.messaging.contract.MessagingContractTest;
import com.stove.common.messaging.delivery.InPlaceDelivery;
import com.stove.common.messaging.delivery.SubscribedHandler;
import com.stove.common.messaging.trace.ReplayTracing;
import com.stove.common.testcontainers.InfraContainers;
import java.time.Duration;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * RabbitMQ Streams adapter 가 공통 계약(결정 202·206)을 지키는지 실제 RabbitMQ(stream 플러그인)·MySQL 로 본다(결정 211).
 * Streams 고유 동작(소비자가 붙기 전 발행한 메시지도 받는다)도 여기서 본다.
 */
@SpringBootTest(properties = "stove.outbox.relay-enabled=false")
@Import(InfraContainers.MySql.class)
class RabbitStreamsContractTest extends MessagingContractTest {

    /** 평소 3, S4 측정 때만 6(결정 218, {@link MessagingContractTest#partitions()}). */
    private static final int PARTITIONS = partitions();
    private static final Duration MAX_AGE = Duration.ofHours(1);
    private static final List<Duration> SHORT_RETRIES = List.of(Duration.ofMillis(10), Duration.ofMillis(10), Duration.ofMillis(10));
    private static final GenericContainer<?> RABBIT = new GenericContainer<>("rabbitmq:4.1-management-alpine")
            .withExposedPorts(5552)
            .withCommand("sh", "-c", "rabbitmq-plugins enable --offline rabbitmq_stream && exec rabbitmq-server")
            .waitingFor(Wait.forLogMessage(".*Server startup complete.*", 1))
            .withStartupTimeout(Duration.ofMinutes(2));

    static {
        RABBIT.start();
    }

    private Environment environment;
    private RabbitStreamsPublisher publisher;

    /**
     * 연결은 처음 쓸 때 연다. 상위 클래스의 {@code @BeforeEach}(토픽 준비)가 하위 클래스의 {@code @BeforeEach} 보다 먼저 돌기 때문이다.
     */
    private Environment environment() {
        if (environment == null) {
            environment = RabbitStreamsMessagingAutoConfiguration.environment(RABBIT.getHost(), RABBIT.getMappedPort(5552),
                    "guest", "guest");
            publisher = new RabbitStreamsPublisher(environment, PARTITIONS, MAX_AGE);
        }
        return environment;
    }

    @Override
    protected void prepareTopic(String topic) {
        RabbitStreamsTopology.declare(environment(), topic, PARTITIONS, MAX_AGE);
    }

    @Override
    protected void closeAdapter() {
        if (environment != null) {
            publisher.close();
            environment.close();
            environment = null;
            publisher = null;
        }
    }

    @Override
    protected AutoCloseable subscribe(String group, String topic, KeyQuarantine storage, EventHandler handler,
                                      List<Class<? extends RuntimeException>> noRetryFor) {
        RabbitStreamsSubscriptions subscriptions = new RabbitStreamsSubscriptions(
                () -> List.of(new SubscribedHandler(group, topic, handler, asArray(noRetryFor))), environment(),
                new InPlaceDelivery(storage, ReplayTracing.NONE, retryDelays(SHORT_RETRIES)), PARTITIONS, MAX_AGE, Duration.ofMillis(20));
        subscriptions.start();
        return subscriptions::stop;
    }

    @Override
    protected Optional<String> keyInSamePartition(String key) {
        return keyWhere(candidate -> RabbitStreamsTopology.partition(candidate, PARTITIONS) == RabbitStreamsTopology.partition(key, PARTITIONS));
    }

    @Override
    protected Optional<String> keyInOtherPartition(String key) {
        return keyWhere(candidate -> RabbitStreamsTopology.partition(candidate, PARTITIONS) != RabbitStreamsTopology.partition(key, PARTITIONS));
    }

    @Override
    protected void publish(OutboundMessage message) {
        environment();
        publisher.publish(message).orTimeout(10, TimeUnit.SECONDS).join();
    }

    /** Streams 고유: 분할이 로그라 소비자가 붙기 전에 보낸 메시지도 새 그룹이 처음부터 받는다(classic queue 는 버린다). */
    @Test
    void 소비자가_붙기_전에_보낸_메시지도_새_그룹이_처음부터_받는다() {
        String key = "K-" + UUID.randomUUID();
        String before = send(key);
        Recording late = new Recording();
        start("late-" + UUID.randomUUID().toString().substring(0, 8), late);
        String after = send(key);

        await("두 건 수신", () -> late.ids().size() >= 2);
        settle();
        assertThat(late.ids()).containsExactly(before, after);
    }
}
