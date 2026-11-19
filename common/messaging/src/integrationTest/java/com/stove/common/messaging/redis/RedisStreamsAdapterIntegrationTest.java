package com.stove.common.messaging.redis;

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
import io.lettuce.core.RedisClient;
import io.lettuce.core.XGroupCreateArgs;
import io.lettuce.core.XReadArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.GenericContainer;

/**
 * Redis Streams adapter(결정 203)가 결정 202 의 보장을 지키는지 실제 Redis·MySQL 로 본다:
 * 같은 키는 보낸 순서대로 한 번씩, 같은 그룹 인스턴스가 둘이어도 스트림마다 한 곳만 읽음,
 * 앞 점유자가 처리하다 만(미확인) 항목을 이어받아 처리, 실패는 제자리 재시도 뒤 격리, 보관 실패 땐 메시지를 붙잡음.
 */
@SpringBootTest(properties = "stove.outbox.relay-enabled=false")
@Import({InfraContainers.MySql.class, InfraContainers.Kafka.class, InfraContainers.Redis.class})
class RedisStreamsAdapterIntegrationTest {

    private static final int STREAMS = 3;

    @Autowired
    KeyQuarantine quarantine;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    GenericContainer<?> redisContainer;

    private RedisClient client;
    private StatefulRedisConnection<String, String> connection;
    private RedisStreamsPublisher publisher;
    private final List<RedisStreamsSubscriptions> started = new ArrayList<>();
    private String group;
    private String topic;
    private Duration lease = Duration.ofSeconds(60);
    /** 구독을 시작하기 전에 관측 훅을 걸 때 쓴다(시작 뒤에 걸면 이미 처리한 항목을 놓친다). */
    private Consumer<RedisStreamsSubscriptions> beforeStart = subscriptions -> {
    };

    @BeforeEach
    void setUp() {
        client = RedisClient.create("redis://" + redisContainer.getHost() + ":" + redisContainer.getMappedPort(6379));
        connection = client.connect();
        publisher = new RedisStreamsPublisher(connection, STREAMS);
        group = "fixture-redis-" + UUID.randomUUID();
        topic = "fixture.redis." + UUID.randomUUID().toString().substring(0, 8);
    }

    @AfterEach
    void tearDown() {
        started.forEach(RedisStreamsSubscriptions::stop);
        connection.close();
        client.shutdown();
    }

    private RedisStreamsSubscriptions subscribe(KeyQuarantine storage, EventHandler handler,
                                                Class<? extends RuntimeException>[] noRetryFor) {
        RedisStreamsSubscriptions subscriptions = new RedisStreamsSubscriptions(
                () -> List.of(new SubscribedHandler(group, topic, handler, noRetryFor)), client,
                new InPlaceDelivery(storage, ReplayTracing.NONE,
                        List.of(Duration.ofMillis(10), Duration.ofMillis(10), Duration.ofMillis(10))),
                STREAMS, 10, 200, Duration.ofMillis(20), lease);
        beforeStart.accept(subscriptions);
        subscriptions.start();
        started.add(subscriptions);
        return subscriptions;
    }

    @SuppressWarnings("unchecked")
    private RedisStreamsSubscriptions subscribe(EventHandler handler) {
        return subscribe(quarantine, handler, new Class[0]);
    }

    private String publish(String key) {
        String eventId = UUID.randomUUID().toString();
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(EventHeaders.EVENT_ID, eventId);
        headers.put(EventHeaders.EVENT_TYPE, "FixtureHappened");
        headers.put(EventHeaders.OCCURRED_AT, Instant.now().toString());
        publisher.publish(new OutboundMessage(topic, key, "{\"key\":\"" + key + "\"}", headers))
                .orTimeout(10, TimeUnit.SECONDS).join();
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
    void sameKeyArrivesOnceInPublicationOrderAcrossStreams() {
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
    }

    @Test
    void messagesPublishedBeforeTheGroupExistsAreStillDelivered() {
        // 스트림은 로그처럼 남는다: 소비 그룹이 나중에 생겨도 처음부터 읽는다(RabbitMQ 와 다른 점).
        String early = publish("K-early");
        Recording handler = new Recording();
        subscribe(handler);

        await("먼저 보낸 메시지 수신", () -> handler.ids().contains(early));
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
        assertThat(all).as("스트림마다 한 인스턴스만 읽어 같은 메시지를 둘이 받지 않는다")
                .containsExactlyInAnyOrderElementsOf(sent);
    }

    @Test
    void anEntryTheFormerOwnerReadButDidNotAcknowledgeIsProcessedByTheNextOwner() {
        String key = "K-" + UUID.randomUUID();
        String inFlight = publish(key);
        String stream = RedisStreamTopology.stream(topic, RedisStreamTopology.shard(key, STREAMS));
        // 앞 점유자가 읽기만 하고 확인(XACK) 전에 죽었다.
        connection.sync().xgroupCreate(XReadArgs.StreamOffset.from(stream, "0"), group, XGroupCreateArgs.Builder.mkstream());
        // java.util.function.Consumer 와 이름이 겹쳐 Lettuce 쪽만 전체 이름으로 쓴다.
        connection.sync().xreadgroup(io.lettuce.core.Consumer.from(group, RedisStreamTopology.CONSUMER), XReadArgs.Builder.count(10),
                XReadArgs.StreamOffset.lastConsumed(stream));
        String after = publish(key);
        Recording handler = new Recording();

        subscribe(handler);

        await("이어받아 두 건 처리", () -> handler.received.size() >= 2);
        assertThat(handler.idsOf(key)).as("처리하다 만 항목부터, 원래 순서대로").containsExactly(inFlight, after);
        assertThat(connection.sync().xpending(stream, group).getCount()).isZero();
    }

    @Test
    @SuppressWarnings("unchecked")
    void aFailureIsRetriedInPlaceThenQuarantinedAndStopsOnlyItsKey() {
        String broken = "K-broken-" + UUID.randomUUID();
        String healthy = "K-healthy-" + UUID.randomUUID();
        Recording handler = new Recording();
        handler.failKey = broken;
        subscribe(quarantine, handler, new Class[]{Rejected.class});

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
    }

    @Test
    @SuppressWarnings("unchecked")
    void whenStoringTheFailureFailsTheEntryIsHeldSoALaterEntryOfTheKeyCannotOvertakeIt() {
        String key = "K-" + UUID.randomUUID();
        Recording handler = new Recording();
        handler.failKey = key;
        FlakyQuarantine storage = new FlakyQuarantine(quarantine, 2);
        subscribe(storage, handler, new Class[0]);

        String first = publish(key);
        String second = publish(key);

        await("두 건 모두 보관", () -> jdbc.queryForObject("SELECT COUNT(*) FROM consumer_quarantine WHERE consumer_group = ?",
                Integer.class, group) >= 2);
        assertThat(storage.failures).isEqualTo(2);
        assertThat(handler.attempts(second)).isZero();
        assertThat(jdbc.queryForObject("SELECT event_id FROM consumer_quarantine WHERE consumer_group = ? AND failure = 'KEY_STOPPED'",
                String.class, group)).isEqualTo(second);
        assertThat(jdbc.queryForObject("SELECT event_id FROM consumer_quarantine WHERE consumer_group = ? AND failure <> 'KEY_STOPPED'",
                String.class, group)).isEqualTo(first);
    }

    /*
     * 점유를 잃었을 때(D-058). 잠금 기한은 60초 그대로 두어 연장 스레드(20초 주기)가 끼어들지 못하게 하고,
     * 읽기 스레드 자신의 확인(항목마다 점유 확인, 점유 확인 뒤 XACK, 점유 확인과 함께 그룹 읽기)만으로 결과가 정해지게 한다.
     * 옛 점유자가 실제로 시도했는지는 관측 훅(읽기 거절 횟수, 확인 직후 콜백)으로 기다린다. 일어나면 수 ms 안에 일어날 일만
     * 재시도 주기(1초)보다 긴 창(1.5초) 동안 일어나지 않는지로 본다.
     */

    @Test
    void afterLosingTheLeaseTheFormerOwnerNeitherAcknowledgesNorProcessesWhatItAlreadyRead() {
        String key = "K-" + UUID.randomUUID();
        String stream = RedisStreamTopology.stream(topic, RedisStreamTopology.shard(key, STREAMS));
        String lock = RedisStreamTopology.lockKey(group, stream);
        // 구독 전에 둘 다 보내 첫 읽기(COUNT 10)에 함께 들어오게 한다.
        String first = publish(key);
        String second = publish(key);
        Recording handler = new Recording();
        // 첫 항목을 처리하는 도중 다른 인스턴스가 점유를 가져간다.
        handler.onFirst = () -> connection.sync().set(lock, "another-instance");

        subscribe(handler);
        await("첫 항목 처리 끝", () -> handler.firstDone);
        stays("점유를 잃은 뒤 같이 읽어 둔 뒤 항목을 처리하지 않는다", Duration.ofMillis(1500),
                () -> handler.ids().equals(List.of(first)));
        assertThat(connection.sync().xpending(stream, group).getCount())
                .as("점유를 확인한 뒤에만 확인(XACK)하므로 함께 읽은 두 항목이 모두 미확인으로 남는다").isEqualTo(2);

        connection.sync().del(lock);
        await("이어서 처리", () -> handler.idsOf(key).size() >= 3);
        assertThat(handler.idsOf(key)).as("다시 점유하면 미확인 항목부터 원래 순서대로").containsExactly(first, first, second);
    }

    @Test
    void afterLosingTheLeaseTheFormerOwnerDoesNotTakeNewEntriesFromTheGroup() {
        String key = "K-" + UUID.randomUUID();
        String stream = RedisStreamTopology.stream(topic, RedisStreamTopology.shard(key, STREAMS));
        String lock = RedisStreamTopology.lockKey(group, stream);
        Recording handler = new Recording();
        RedisStreamsSubscriptions subscriptions = subscribe(handler);
        String first = publish(key);
        await("첫 항목 처리·확인", () -> handler.firstDone && connection.sync().xpending(stream, group).getCount() == 0);

        // 쉬는 동안(새 항목을 기다리는 중) 다른 인스턴스가 점유를 가져간 뒤 새 항목이 붙는다.
        connection.sync().set(lock, "another-instance");
        String second = publish(key);
        await("옛 점유자가 깨어나 점유 확인 읽기를 시도했다가 거절됨", () -> subscriptions.lostReads() >= 1);

        assertThat(handler.ids()).containsExactly(first);
        assertThat(connection.sync().xpending(stream, group).getCount())
                .as("점유 확인과 함께 읽으므로 옛 점유자는 새 항목을 그룹에서 가져가지 않는다").isZero();

        connection.sync().del(lock);
        await("이어서 처리", () -> handler.idsOf(key).size() >= 2);
        assertThat(handler.idsOf(key)).containsExactly(first, second);
    }

    @Test
    void theOwnershipIsCheckedBeforeEveryEntryNotOnlyWhenTheBatchIsRead() {
        String key = "K-" + UUID.randomUUID();
        String stream = RedisStreamTopology.stream(topic, RedisStreamTopology.shard(key, STREAMS));
        String lock = RedisStreamTopology.lockKey(group, stream);
        // 둘 다 같은 읽기에 들어오게 구독 전에 보낸다.
        String first = publish(key);
        String second = publish(key);
        Recording handler = new Recording();
        // 첫 항목을 확인(XACK)한 직후, 둘째 항목을 처리하기 전에 점유가 넘어간다. 훅은 읽기 시작 전에 건다.
        AtomicBoolean once = new AtomicBoolean();
        CountDownLatch stolen = new CountDownLatch(1);
        beforeStart = subscriptions -> subscriptions.onAcknowledged(id -> {
            if (once.compareAndSet(false, true)) {
                connection.sync().set(lock, "another-instance");
                stolen.countDown();
            }
        });
        subscribe(handler);

        await("첫 항목 확인 뒤 점유가 넘어감", () -> stolen.getCount() == 0);
        // 항목마다 확인하지 않으면 같은 반복에서 곧바로 둘째 항목을 처리한다(수 ms). 재시도 주기(1초)보다 긴 창으로 본다.
        stays("같이 읽어 둔 둘째 항목을 점유를 잃은 뒤 처리하지 않는다", Duration.ofMillis(1500),
                () -> handler.ids().equals(List.of(first)));
        assertThat(connection.sync().xpending(stream, group).getCount()).as("둘째 항목만 미확인").isEqualTo(1);

        connection.sync().del(lock);
        await("이어서 처리", () -> handler.idsOf(key).size() >= 2);
        assertThat(handler.idsOf(key)).containsExactly(first, second);
    }

    /** {@code duration} 동안 조건이 계속 참인지. 한 번이라도 거짓이면 실패. */
    private static void stays(String what, Duration duration, BooleanSupplier condition) {
        long deadline = System.nanoTime() + duration.toNanos();
        while (System.nanoTime() < deadline) {
            if (!condition.getAsBoolean()) {
                throw new AssertionError("유지되지 않았다: " + what);
            }
            sleep(Duration.ofMillis(50));
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

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
        /** 첫 수신 때 한 번 실행한다(처리 도중의 상황을 만든다). */
        volatile Runnable onFirst;
        /** 첫 수신의 처리(onFirst 포함)가 끝났다. */
        volatile boolean firstDone;

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
            received.add(message);
            Runnable once = onFirst;
            if (once != null) {
                onFirst = null;
                once.run();
            }
            firstDone = true;
        }
    }
}
