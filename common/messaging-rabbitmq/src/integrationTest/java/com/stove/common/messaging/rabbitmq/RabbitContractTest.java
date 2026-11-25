package com.stove.common.messaging.rabbitmq;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rabbitmq.client.SocketConfigurator;
import com.rabbitmq.client.SocketConfigurators;
import com.stove.common.contract.EventHeaders;
import com.stove.common.event.EventHandler;
import com.stove.common.event.KeyQuarantine;
import com.stove.common.event.OutboundMessage;
import com.stove.common.messaging.contract.MessagingContractTest;
import com.stove.common.messaging.delivery.InPlaceDelivery;
import com.stove.common.messaging.delivery.SubscribedHandler;
import com.stove.common.messaging.trace.ReplayTracing;
import com.stove.common.testcontainers.InfraContainers;
import com.stove.common.testcontainers.SharedContainers;
import java.io.IOException;
import java.net.Socket;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.containers.ContainerState;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/** RabbitMQ adapter 가 공통 계약(결정 202·206)을 지키는지 실제 RabbitMQ·MySQL 로 본다(결정 209). RabbitMQ 고유 동작(받을 큐 없는 발행)도 여기서 본다. */
@SpringBootTest(properties = "stove.outbox.relay-enabled=false")
@Import(InfraContainers.MySql.class)
class RabbitContractTest extends MessagingContractTest {

    /** 평소 3, S4 측정 때만 6(결정 218, {@link MessagingContractTest#partitions()}). */
    private static final int QUEUES = partitions();
    /** 이 adapter 로 받는 소비자가 없다고 설정한 토픽. */
    private static final String OPTIONAL_TOPIC = "fixture.optional.v1";
    private static final List<Duration> SHORT_RETRIES = List.of(Duration.ofMillis(10), Duration.ofMillis(10), Duration.ofMillis(10));
    /** S3 측정 때만 메모리 1GB·메모리 경보 한도의 40% 명시(결정 222, PR #242 R3). */
    private static final GenericContainer<?> RABBIT = SharedContainers.rabbitForS3(
            new GenericContainer<>("rabbitmq:4.1-management-alpine")
                    .withExposedPorts(5672)
                    .waitingFor(Wait.forLogMessage(".*Server startup complete.*", 1))
                    .withStartupTimeout(Duration.ofMinutes(2)));

    static {
        RABBIT.start();
    }

    private CachingConnectionFactory connectionFactory;
    private RabbitAdmin admin;
    private RabbitMessagePublisher publisher;
    /** S3 측정 때만: 발행 전용 연결. 메모리 경보는 발행하는 연결을 막으므로 재개 소비자와 연결을 나눈다(PR #242 R2). */
    private CachingConnectionFactory publishFactory;
    /** S3 측정 때만: 발행 연결의 소켓. 막힌 송신은 소켓을 닫아야 풀린다. */
    private final List<Socket> publishSockets = new CopyOnWriteArrayList<>();

    @BeforeEach
    void connect() {
        connectionFactory = new CachingConnectionFactory(RABBIT.getHost(), RABBIT.getMappedPort(5672));
        connectionFactory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        connectionFactory.setPublisherReturns(true);
        admin = new RabbitAdmin(connectionFactory);
        publisher = new RabbitMessagePublisher(connectionFactory, admin, QUEUES, Set.of(OPTIONAL_TOPIC));
        if (SharedContainers.s3Measurement()) {
            // 실제 시스템도 발행(생산 앱 릴레이)과 소비(소비 앱)가 다른 연결이다. 평소 계약 테스트는 한 연결을 그대로 쓴다.
            publishFactory = new CachingConnectionFactory(RABBIT.getHost(), RABBIT.getMappedPort(5672));
            publishFactory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
            publishFactory.setPublisherReturns(true);
            SocketConfigurator defaults = SocketConfigurators.defaultConfigurator();
            publishFactory.getRabbitConnectionFactory().setSocketConfigurator((Socket socket) -> {
                defaults.configure(socket);
                publishSockets.add(socket);
            });
            publisher = new RabbitMessagePublisher(publishFactory, new RabbitAdmin(publishFactory), QUEUES, Set.of(OPTIONAL_TOPIC));
        }
    }

    @Override
    protected Optional<ContainerState> brokerContainer() {
        return Optional.of(RABBIT);
    }

    @Override
    protected String brokerDataDir() {
        return "/var/lib/rabbitmq";
    }

    /** 실제로 적용된 메모리 경보 값(PR #242 R3). */
    @Override
    protected Map<String, String> brokerSettings() throws IOException, InterruptedException {
        return SharedContainers.rabbitMemorySettings(RABBIT);
    }

    /** 경보 중 동기 송신({@code RabbitTemplate.send})은 TCP 송신 버퍼가 차면 돌아오지 않는다(PR #242 R2). */
    @Override
    protected boolean publishCallMayBlock() {
        return true;
    }

    /** 발행 연결의 소켓을 닫아 막힌 송신을 풀고 발행 연결을 버린다. 재개 소비자는 다른 연결({@link #connectionFactory})이다. */
    @Override
    protected void abandonBlockedPublisher() throws IOException {
        for (Socket socket : publishSockets) {
            socket.close();
        }
        if (publishFactory != null) {
            publishFactory.destroy();
        }
    }

    @Override
    protected void closeAdapter() {
        connectionFactory.destroy();
        if (publishFactory != null) {
            publishFactory.destroy();
            publishFactory = null;
            publishSockets.clear();
        }
    }

    @Override
    protected AutoCloseable subscribe(String group, String topic, KeyQuarantine storage, EventHandler handler,
                                      List<Class<? extends RuntimeException>> noRetryFor) {
        RabbitSubscriptions subscriptions = new RabbitSubscriptions(
                () -> List.of(new SubscribedHandler(group, topic, handler, asArray(noRetryFor))), connectionFactory, admin,
                new InPlaceDelivery(storage, ReplayTracing.NONE, retryDelays(SHORT_RETRIES)), QUEUES, 10, Duration.ofMillis(20));
        subscriptions.start();
        return subscriptions::stop;
    }

    @Override
    protected Optional<String> keyInSamePartition(String key) {
        return keyWhere(candidate -> RabbitTopology.shard(candidate, QUEUES) == RabbitTopology.shard(key, QUEUES));
    }

    @Override
    protected Optional<String> keyInOtherPartition(String key) {
        return keyWhere(candidate -> RabbitTopology.shard(candidate, QUEUES) != RabbitTopology.shard(key, QUEUES));
    }

    @Override
    protected void publish(OutboundMessage message) {
        publisher.publish(message).orTimeout(10, TimeUnit.SECONDS).join();
    }

    private CompletableFuture<Void> sendTo(String toTopic) {
        Map<String, String> headers = Map.of(EventHeaders.EVENT_ID, UUID.randomUUID().toString(),
                EventHeaders.EVENT_TYPE, "FixtureHappened", EventHeaders.OCCURRED_AT, Instant.now().toString());
        return publisher.publish(new OutboundMessage(toTopic, "K", "{}", headers));
    }

    /** RabbitMQ 고유: 받을 큐가 없으면 메시지가 버려진다(Kafka·Redis 는 로그에 남는다). 그래서 발행을 실패로 돌린다. */
    @Test
    void aMessageNoQueueCanTakeFailsSoTheRelayKeepsItUnlessTheTopicHasNoRabbitConsumers() {
        // 소비 앱이 아직 큐를 선언하지 않았다: 성공으로 치면 SENT 뒤에 사라진다(PR #211 R3).
        assertThatThrownBy(() -> sendTo("fixture.unbound." + UUID.randomUUID().toString().substring(0, 8))
                .orTimeout(10, TimeUnit.SECONDS).join())
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasMessageContaining("되돌아왔다");
        // 이 adapter 로 받는 소비자가 없다고 설정한 토픽은 그대로 성공(Kafka 에서도 아무도 읽지 않는 토픽).
        sendTo(OPTIONAL_TOPIC).orTimeout(10, TimeUnit.SECONDS).join();
    }
}
