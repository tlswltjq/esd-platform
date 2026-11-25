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
import com.stove.common.testcontainers.SharedContainers;
import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.containers.ContainerState;
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
    /**
     * 분할 로그 보존 기간. 평소 1시간. S3 측정 때는 사실상 끈다(PR #242 R5): 느린 측정에서 재개 전에 오래된 세그먼트가 만료되면
     * 보존 만료가 {@code lost} 에 섞인다. 클라이언트가 보존 없음({@code null}·0)을 받지 않으므로 10년으로 둔다. 길이 제한
     * ({@code max-length-bytes})은 원래 걸지 않는다.
     */
    private static final Duration MAX_AGE = SharedContainers.s3Measurement() ? Duration.ofDays(3650) : Duration.ofHours(1);
    private static final List<Duration> SHORT_RETRIES = List.of(Duration.ofMillis(10), Duration.ofMillis(10), Duration.ofMillis(10));
    /** S3 측정 때만 메모리 1GB·메모리 경보 한도의 40% 명시(결정 222, PR #242 R3). */
    private static final GenericContainer<?> RABBIT = SharedContainers.rabbitForS3(
            new GenericContainer<>("rabbitmq:4.1-management-alpine")
                    .withExposedPorts(5552)
                    .withCommand("sh", "-c", "rabbitmq-plugins enable --offline rabbitmq_stream && exec rabbitmq-server")
                    .waitingFor(Wait.forLogMessage(".*Server startup complete.*", 1))
                    .withStartupTimeout(Duration.ofMinutes(2)));

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
    protected Optional<ContainerState> brokerContainer() {
        return Optional.of(RABBIT);
    }

    @Override
    protected String brokerDataDir() {
        return "/var/lib/rabbitmq";
    }

    /** 실제로 적용된 메모리 경보 값(PR #242 R3)과 분할 보존 기간(PR #242 R5). */
    @Override
    protected Map<String, String> brokerSettings() throws IOException, InterruptedException {
        Map<String, String> settings = new LinkedHashMap<>(SharedContainers.rabbitMemorySettings(RABBIT));
        settings.put("max_age_seconds", Long.toString(MAX_AGE.toSeconds()));
        return settings;
    }

    /**
     * 생산자는 확인되지 않은 메시지가 한도(기본 1만 건)에 닿으면 {@code send} 안에서 자리가 날 때까지 기다린다. 경보로 확인이
     * 멈추면 호출이 오래 돌아오지 않을 수 있어 같은 제한을 건다(PR #242 R2).
     */
    @Override
    protected boolean publishCallMayBlock() {
        return true;
    }

    /** 막힌 생산자를 닫는다. 재개 소비자는 Environment 의 소비 연결을 쓴다(생산자 연결과 따로다). */
    @Override
    protected void abandonBlockedPublisher() {
        if (publisher != null) {
            publisher.close();
        }
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
