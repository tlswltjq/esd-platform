package com.stove.common.messaging.rabbitmq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stove.common.event.EventHandler;
import com.stove.common.event.InboundMessage;
import com.stove.common.event.KeyQuarantine;
import com.stove.common.event.OutboundMessage;
import com.stove.common.contract.EventHeaders;
import com.stove.common.messaging.delivery.InPlaceDelivery;
import com.stove.common.messaging.delivery.SubscribedHandler;
import com.stove.common.messaging.trace.ReplayTracing;
import com.stove.common.testcontainers.InfraContainers;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * RabbitMQ adapter(결정 203)가 결정 202 의 보장을 지키는지 실제 RabbitMQ·MySQL 로 본다:
 * 발행은 브로커 confirm 뒤 성공, 같은 키는 보낸 순서대로 한 번씩, 같은 그룹 인스턴스가 둘이어도 큐마다 한 소비자,
 * 실패는 제자리 재시도 뒤 격리하고 그 키만 멈춤.
 */
@SpringBootTest(properties = "stove.outbox.relay-enabled=false")
@Import({InfraContainers.MySql.class, InfraContainers.Kafka.class})
class RabbitAdapterIntegrationTest {

    private static final int QUEUES = 3;
    private static final GenericContainer<?> RABBIT = new GenericContainer<>("rabbitmq:4.1-management-alpine")
            .withExposedPorts(5672)
            .waitingFor(Wait.forLogMessage(".*Server startup complete.*", 1))
            .withStartupTimeout(Duration.ofMinutes(2));

    static {
        RABBIT.start();
    }

    @Autowired
    KeyQuarantine quarantine;

    @Autowired
    JdbcTemplate jdbc;

    private CachingConnectionFactory connectionFactory;
    private RabbitAdmin admin;
    private RabbitMessagePublisher publisher;
    private final List<RabbitSubscriptions> started = new ArrayList<>();
    private String group;
    private String topic;

    @BeforeEach
    void setUp() {
        connectionFactory = new CachingConnectionFactory(RABBIT.getHost(), RABBIT.getMappedPort(5672));
        connectionFactory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        connectionFactory.setPublisherReturns(true);
        admin = new RabbitAdmin(connectionFactory);
        publisher = new RabbitMessagePublisher(connectionFactory, admin, QUEUES, Set.of("fixture.optional.v1"));
        group = "fixture-rabbit-" + UUID.randomUUID();
        topic = "fixture.rabbit." + UUID.randomUUID().toString().substring(0, 8);
    }

    @AfterEach
    void tearDown() {
        started.forEach(RabbitSubscriptions::stop);
        connectionFactory.destroy();
    }

    private RabbitSubscriptions subscribe(EventHandler handler, Class<? extends RuntimeException>... noRetryFor) {
        return subscribe(quarantine, handler, noRetryFor);
    }

    private RabbitSubscriptions subscribe(KeyQuarantine storage, EventHandler handler,
                                          Class<? extends RuntimeException>... noRetryFor) {
        RabbitSubscriptions subscriptions = new RabbitSubscriptions(
                () -> List.of(new SubscribedHandler(group, topic, handler, noRetryFor)), connectionFactory, admin,
                new InPlaceDelivery(storage, ReplayTracing.NONE,
                        List.of(Duration.ofMillis(10), Duration.ofMillis(10), Duration.ofMillis(10))),
                QUEUES, 10, Duration.ofMillis(20));
        subscriptions.start();
        started.add(subscriptions);
        return subscriptions;
    }

    private String publish(String key) {
        String eventId = UUID.randomUUID().toString();
        send(topic, key, eventId).orTimeout(10, TimeUnit.SECONDS).join();
        return eventId;
    }

    private CompletableFuture<Void> send(String toTopic, String key, String eventId) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(EventHeaders.EVENT_ID, eventId);
        headers.put(EventHeaders.EVENT_TYPE, "FixtureHappened");
        headers.put(EventHeaders.OCCURRED_AT, Instant.now().toString());
        return publisher.publish(new OutboundMessage(toTopic, key, "{\"key\":\"" + key + "\"}", headers));
    }

    private static void await(String what, BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("기다렸지만 이뤄지지 않았다: " + what);
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
        }
    }

    @Test
    void sameKeyArrivesOnceInPublicationOrderAcrossShards() {
        Recording handler = new Recording();
        subscribe(handler);
        Map<String, List<String>> sentByKey = new LinkedHashMap<>();
        for (int round = 0; round < 5; round++) {
            for (int k = 0; k < 6; k++) {
                String key = "K-" + k;
                sentByKey.computeIfAbsent(key, ignored -> new ArrayList<>()).add(publish(key));
            }
        }

        await("30건 수신", () -> handler.received.size() >= 30);

        assertThat(handler.received).hasSize(30);
        for (Map.Entry<String, List<String>> sent : sentByKey.entrySet()) {
            assertThat(handler.idsOf(sent.getKey())).as("키 %s 는 보낸 순서대로 한 번씩", sent.getKey())
                    .containsExactlyElementsOf(sent.getValue());
        }
        assertThat(handler.received.get(0).payload()).startsWith("{\"key\":");
    }

    @Test
    void twoInstancesOfTheSameGroupDoNotBothReceiveAMessage() {
        Recording first = new Recording();
        Recording second = new Recording();
        subscribe(first);
        subscribe(second);
        List<String> sent = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            sent.add(publish("K-" + (i % 4)));
        }

        await("20건 수신", () -> first.received.size() + second.received.size() >= 20);

        List<String> all = new ArrayList<>(first.ids());
        all.addAll(second.ids());
        assertThat(all).as("큐마다 활성 소비자는 하나라 같은 메시지를 두 인스턴스가 받지 않는다")
                .containsExactlyInAnyOrderElementsOf(sent);
    }

    @Test
    void aFailureIsRetriedInPlaceThenQuarantinedAndStopsOnlyItsKey() {
        String broken = "K-broken-" + UUID.randomUUID();
        String healthy = "K-healthy-" + UUID.randomUUID();
        Recording handler = new Recording();
        handler.failKey = broken;
        subscribe(handler);

        String failing = publish(broken);
        String behind = publish(broken);
        String other = publish(healthy);

        await("격리 2건", () -> jdbc.queryForObject("SELECT COUNT(*) FROM consumer_quarantine WHERE consumer_group = ?",
                Integer.class, group) >= 2);
        await("다른 키 수신", () -> handler.ids().contains(other));

        assertThat(handler.attempts(failing)).as("최초 1회 + 재시도 3회").isEqualTo(4);
        assertThat(handler.attempts(behind)).as("멈춘 키의 뒤 메시지는 처리하지 않고 보관").isZero();
        assertThat(quarantine.stopped(group, topic, broken)).isTrue();
        assertThat(quarantine.stopped(group, topic, healthy)).isFalse();
        assertThat(jdbc.queryForObject("SELECT event_id FROM consumer_quarantine WHERE consumer_group = ? AND failure = 'KEY_STOPPED'",
                String.class, group)).isEqualTo(behind);
    }

    @Test
    void noRetryExceptionsAreQuarantinedOnTheFirstFailure() {
        String key = "K-" + UUID.randomUUID();
        Recording handler = new Recording();
        handler.rejectKey = key;
        subscribe(handler, Rejected.class);

        String rejected = publish(key);

        await("격리", () -> quarantine.stopped(group, topic, key));
        assertThat(handler.attempts(rejected)).isEqualTo(1);
    }

    @Test
    void aMessageNoQueueCanTakeFailsSoTheRelayKeepsItUnlessTheTopicHasNoRabbitConsumers() {
        // 소비 앱이 아직 큐를 선언하지 않았다: 성공으로 치면 SENT 뒤에 사라진다(PR #211 R3).
        assertThatThrownBy(() -> send("fixture.unbound." + UUID.randomUUID().toString().substring(0, 8), "K",
                UUID.randomUUID().toString()).orTimeout(10, TimeUnit.SECONDS).join())
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasMessageContaining("되돌아왔다");
        // 이 adapter 로 받는 소비자가 없는 토픽은 그대로 성공(Kafka 에서도 아무도 읽지 않는 토픽).
        send("fixture.optional.v1", "K", UUID.randomUUID().toString()).orTimeout(10, TimeUnit.SECONDS).join();
    }

    @Test
    void whenStoringTheFailureFailsTheMessageIsHeldSoALaterMessageOfTheKeyCannotOvertakeIt() {
        String key = "K-" + UUID.randomUUID();
        Recording handler = new Recording();
        handler.failKey = key;
        FlakyQuarantine storage = new FlakyQuarantine(quarantine, 2);
        subscribe(storage, handler);

        String first = publish(key);
        String second = publish(key);

        await("두 건 모두 보관", () -> jdbc.queryForObject("SELECT COUNT(*) FROM consumer_quarantine WHERE consumer_group = ?",
                Integer.class, group) >= 2);
        assertThat(storage.failures).as("앞 메시지의 보관이 두 번 실패했다").isEqualTo(2);
        assertThat(handler.attempts(second)).as("앞 메시지가 붙잡혀 있는 동안 뒤 메시지는 처리되지 않고, 키 정지 뒤 보관된다").isZero();
        assertThat(jdbc.queryForObject("SELECT event_id FROM consumer_quarantine WHERE consumer_group = ? AND failure <> 'KEY_STOPPED'",
                String.class, group)).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT event_id FROM consumer_quarantine WHERE consumer_group = ? AND failure = 'KEY_STOPPED'",
                String.class, group)).isEqualTo(second);
    }

    /** 처음 몇 번의 보관 기록을 실패시킨다(격리 DB 가 잠깐 안 되는 상황). */
    static class FlakyQuarantine implements KeyQuarantine {
        private final KeyQuarantine delegate;
        private final AtomicInteger remainingFailures;
        volatile int failures;

        FlakyQuarantine(KeyQuarantine delegate, int failures) {
            this.delegate = delegate;
            this.remainingFailures = new AtomicInteger(failures);
        }

        @Override
        public boolean stopped(String consumerGroup, String topic, String key) {
            return delegate.stopped(consumerGroup, topic, key);
        }

        @Override
        public void quarantine(String consumerGroup, InboundMessage message, Throwable failure) {
            if (remainingFailures.getAndDecrement() > 0) {
                failures++;
                throw new IllegalStateException("격리 DB 잠깐 안 됨");
            }
            delegate.quarantine(consumerGroup, message, failure);
        }
    }

    static class Rejected extends RuntimeException {
        Rejected() {
            super("rejected");
        }
    }

    static class Recording implements EventHandler {
        final List<InboundMessage> received = Collections.synchronizedList(new ArrayList<>());
        final List<String> tried = Collections.synchronizedList(new ArrayList<>());
        volatile String failKey;
        volatile String rejectKey;

        List<String> ids() {
            synchronized (received) {
                return received.stream().map(message -> message.header(EventHeaders.EVENT_ID)).toList();
            }
        }

        List<String> idsOf(String key) {
            synchronized (received) {
                return received.stream().filter(message -> key.equals(message.key()))
                        .map(message -> message.header(EventHeaders.EVENT_ID)).toList();
            }
        }

        int attempts(String eventId) {
            synchronized (tried) {
                return (int) tried.stream().filter(eventId::equals).count();
            }
        }

        @Override
        public void receive(InboundMessage message) {
            tried.add(message.header(EventHeaders.EVENT_ID));
            if (message.key().equals(failKey)) {
                throw new IllegalStateException("boom");
            }
            if (message.key().equals(rejectKey)) {
                throw new Rejected();
            }
            received.add(message);
        }
    }
}
