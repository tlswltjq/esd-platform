package com.stove.common.messaging.rabbitmq;

import static org.assertj.core.api.Assertions.assertThat;

import com.stove.common.event.EventHandler;
import com.stove.common.event.InboundMessage;
import com.stove.common.event.KeyQuarantine;
import com.stove.common.event.OutboundMessage;
import com.stove.common.kafka.EventHeaders;
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
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
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
        admin = new RabbitAdmin(connectionFactory);
        publisher = new RabbitMessagePublisher(new RabbitTemplate(connectionFactory), admin, QUEUES);
        group = "fixture-rabbit-" + UUID.randomUUID();
        topic = "fixture.rabbit." + UUID.randomUUID().toString().substring(0, 8);
    }

    @AfterEach
    void tearDown() {
        started.forEach(RabbitSubscriptions::stop);
        connectionFactory.destroy();
    }

    private RabbitSubscriptions subscribe(EventHandler handler, Class<? extends RuntimeException>... noRetryFor) {
        RabbitSubscriptions subscriptions = new RabbitSubscriptions(
                () -> List.of(new SubscribedHandler(group, topic, handler, noRetryFor)), connectionFactory, admin,
                new InPlaceDelivery(quarantine, ReplayTracing.NONE,
                        List.of(Duration.ofMillis(10), Duration.ofMillis(10), Duration.ofMillis(10))),
                QUEUES, 10);
        subscriptions.start();
        started.add(subscriptions);
        return subscriptions;
    }

    private String publish(String key) {
        String eventId = UUID.randomUUID().toString();
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(EventHeaders.EVENT_ID, eventId);
        headers.put(EventHeaders.EVENT_TYPE, "FixtureHappened");
        headers.put(EventHeaders.OCCURRED_AT, Instant.now().toString());
        CompletableFuture<Void> confirmed = publisher.publish(
                new OutboundMessage(topic, key, "{\"key\":\"" + key + "\"}", headers));
        confirmed.orTimeout(10, TimeUnit.SECONDS).join();
        return eventId;
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
