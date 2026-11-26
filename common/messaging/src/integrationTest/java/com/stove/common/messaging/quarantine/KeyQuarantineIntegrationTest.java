package com.stove.common.messaging.quarantine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.contract.EventHeaders;
import com.stove.common.messaging.kafka.KeyQuarantine;
import com.stove.common.messaging.quarantine.QuarantineReplay.ReplayResult;
import com.stove.common.messaging.trace.ReplayTracing;
import com.stove.common.testcontainers.InfraContainers;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 결정 155·160·206 — 소비 실패를 소비 모듈 DB 에 보관하고 그 키를 멈추며, 키 단위로 원래 순서대로 다시 넣는다.
 * 실제 MySQL 로 본다(정지 판단과 순서가 SQL 에 달려 있다). 재투입은 운영처럼 등록된 {@code @KafkaListener} 컨테이너의 리스너로 간다.
 */
@SpringBootTest(properties = "stove.outbox.relay-enabled=false")
@Import({InfraContainers.MySql.class, InfraContainers.Kafka.class, KeyQuarantineIntegrationTest.Handlers.class})
class KeyQuarantineIntegrationTest {

    static final String GROUP = "fixture-replay";
    static final String TOPIC = "fixture.replay.v1";

    @Autowired
    KeyQuarantine quarantine;

    @Autowired
    QuarantineReplay replay;

    @Autowired
    QuarantinedMessageRepository repository;

    @Autowired
    RecordingHandler handler;

    @Autowired
    KafkaListenerEndpointRegistry listeners;

    @Autowired
    PlatformTransactionManager transactions;

    @BeforeEach
    void reset() {
        handler.received.clear();
        handler.failOn = null;
        handler.blockOn = null;
    }

    private static ConsumerRecord<String, String> message(String key, String eventId) {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(TOPIC, 0, 1L, key, "{\"n\":\"" + eventId + "\"}");
        header(record, EventHeaders.EVENT_ID, eventId);
        header(record, EventHeaders.EVENT_TYPE, "Fixture");
        header(record, EventHeaders.TRACE_PARENT, "tp-1");
        header(record, EventHeaders.TRACE_PARENT, "tp-2");
        return record;
    }

    private static void header(ConsumerRecord<String, String> record, String name, String value) {
        record.headers().add(name, value.getBytes(StandardCharsets.UTF_8));
    }

    private static String eventId(ConsumerRecord<String, String> record) {
        return EventHeaders.lastValue(record.headers(), EventHeaders.EVENT_ID);
    }

    @Test
    void aFailureStopsOnlyItsOwnKey() {
        String key = "K-" + UUID.randomUUID();
        quarantine.quarantine(GROUP, message(key, "E-" + UUID.randomUUID()), new IllegalStateException("boom"));

        assertThat(quarantine.stopped(GROUP, TOPIC, key)).isTrue();
        assertThat(quarantine.stopped(GROUP, TOPIC, "K-other")).isFalse();
        assertThat(quarantine.stopped("other-group", TOPIC, key)).as("그룹마다 따로 멈춘다").isFalse();
    }

    @Test
    void replayDeliversTheKeyInOriginalOrderAndReleasesIt() {
        String key = "K-" + UUID.randomUUID();
        quarantine.quarantine(GROUP, message(key, "E-1-" + key), new IllegalStateException("boom"));
        quarantine.quarantine(GROUP, message(key, "E-2-" + key), null);
        quarantine.quarantine(GROUP, message(key, "E-3-" + key), null);

        ReplayResult result = replay.replay(GROUP, TOPIC, key);

        assertThat(result).isEqualTo(new ReplayResult(3, 0, null));
        assertThat(handler.received).extracting(KeyQuarantineIntegrationTest::eventId)
                .containsExactly("E-1-" + key, "E-2-" + key, "E-3-" + key);
        assertThat(EventHeaders.values(handler.received.get(0).headers(), EventHeaders.TRACE_PARENT))
                .as("보관된 헤더를 그대로 되살린다(중복 포함)").containsExactly("tp-1", "tp-2");
        assertThat(handler.received.get(0).partition()).as("원래 위치를 되살린다").isZero();
        assertThat(handler.received.get(0).offset()).isEqualTo(1L);
        assertThat(quarantine.stopped(GROUP, TOPIC, key)).isFalse();
        assertThat(repository.findByConsumerGroupAndTopicAndPartitionKeyAndStatusOrderByIdAsc(
                GROUP, TOPIC, key, QuarantineStatus.REPLAYED)).hasSize(3);
    }

    @Test
    void aReplayThatFailsAgainKeepsTheKeyStoppedFromThatMessage() {
        String key = "K-" + UUID.randomUUID();
        quarantine.quarantine(GROUP, message(key, "E-1-" + key), new IllegalStateException("boom"));
        quarantine.quarantine(GROUP, message(key, "E-2-" + key), null);
        handler.failOn = "E-2-" + key;

        ReplayResult result = replay.replay(GROUP, TOPIC, key);

        assertThat(result.replayed()).isEqualTo(1);
        assertThat(result.remaining()).isEqualTo(1);
        assertThat(result.stoppedBecause()).contains("still broken");
        assertThat(quarantine.stopped(GROUP, TOPIC, key)).isTrue();
        assertThat(replay.stoppedKeys()).anySatisfy(stopped -> {
            assertThat(stopped.partitionKey()).isEqualTo(key);
            assertThat(stopped.held()).isEqualTo(1);
        });
    }

    @Test
    void aSecondReplayOfTheSameKeyWaitsForTheFirstInsteadOfRunningConcurrently() throws Exception {
        // PR #209 R2: 같은 키를 두 재투입이 동시에 처리하면 같은 메시지가 겹쳐 돈다.
        String key = "K-" + UUID.randomUUID();
        quarantine.quarantine(GROUP, message(key, "E-1-" + key), new IllegalStateException("boom"));
        quarantine.quarantine(GROUP, message(key, "E-2-" + key), null);
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        handler.blockOn = "E-1-" + key;
        handler.inside = inside;
        handler.release = release;
        ExecutorService first = Executors.newSingleThreadExecutor();
        try {
            Future<ReplayResult> running = first.submit(() -> replay.replay(GROUP, TOPIC, key));
            assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue();

            ReplayResult second = replay.replay(GROUP, TOPIC, key);

            assertThat(second.replayed()).isZero();
            assertThat(second.stoppedBecause()).contains("다른 재투입");
            release.countDown();
            assertThat(running.get(10, TimeUnit.SECONDS)).isEqualTo(new ReplayResult(2, 0, null));
            assertThat(handler.received).extracting(KeyQuarantineIntegrationTest::eventId)
                    .containsExactly("E-1-" + key, "E-2-" + key);
        } finally {
            release.countDown();
            first.shutdownNow();
        }
    }

    @Test
    void oneCallStopsAtItsLimitAndCountsWhatIsLeft() {
        // PR #209 R3: 한도는 처리 건수 기준이어야 한다.
        String key = "K-" + UUID.randomUUID();
        for (int i = 1; i <= 3; i++) {
            quarantine.quarantine(GROUP, message(key, "E-" + i + "-" + key), i == 1 ? new IllegalStateException("boom") : null);
        }
        QuarantineReplay limited = new QuarantineReplay(repository, listeners, new ObjectMapper(), transactions,
                ReplayTracing.NONE, Clock.systemUTC(), 2);

        ReplayResult result = limited.replay(GROUP, TOPIC, key);

        assertThat(result.replayed()).isEqualTo(2);
        assertThat(result.remaining()).isEqualTo(1);
        assertThat(result.stoppedBecause()).contains("최대 건수");
        assertThat(quarantine.stopped(GROUP, TOPIC, key)).isTrue();
    }

    @Test
    void replayRunsInsideTheOriginalTraceContext() {
        // PR #209 R4: 다시 넣은 처리가 남기는 후속 이벤트는 원래 흐름의 트레이스에 이어져야 한다.
        String key = "K-" + UUID.randomUUID();
        quarantine.quarantine(GROUP, message(key, "E-1-" + key), new IllegalStateException("boom"));
        List<String> parents = new ArrayList<>();
        QuarantineReplay traced = new QuarantineReplay(repository, listeners, new ObjectMapper(), transactions,
                (traceParent, work) -> {
                    parents.add(traceParent);
                    work.run();
                }, Clock.systemUTC(), 10);

        traced.replay(GROUP, TOPIC, key);

        assertThat(parents).containsExactly("tp-2");
    }

    @Test
    void aMalformedLongKeyOrEventIdIsStillQuarantinedAndReplayedWithTheOriginalKey() {
        // PR #209 R5: 깨진 메시지의 보관이 길이 초과로 실패하면 오프셋이 넘어가지 않아 파티션 전체가 막힌다.
        String key = "K-" + "x".repeat(150);
        String eventId = "E-" + "y".repeat(80);
        quarantine.quarantine(GROUP, message(key, eventId), new IllegalStateException("malformed"));

        assertThat(quarantine.stopped(GROUP, TOPIC, key)).isTrue();
        assertThat(quarantine.stopped(GROUP, TOPIC, key + "z")).isFalse();
        handler.received.clear();
        assertThat(replay.replay(GROUP, TOPIC, key)).isEqualTo(new ReplayResult(1, 0, null));
        assertThat(handler.received.get(0).key()).isEqualTo(key);
        assertThat(eventId(handler.received.get(0))).isEqualTo(eventId);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Handlers {
        @Bean
        RecordingHandler recordingHandler() {
            return new RecordingHandler();
        }
    }

    /** 운영 리스너와 같은 모양: 그 그룹·토픽의 {@code @KafkaListener}. 재투입이 이 컨테이너의 리스너를 찾아 부른다. */
    static class RecordingHandler {
        final List<ConsumerRecord<String, String>> received = new CopyOnWriteArrayList<>();
        volatile String failOn;
        volatile String blockOn;
        volatile CountDownLatch inside;
        volatile CountDownLatch release;

        @KafkaListener(topics = TOPIC, groupId = GROUP)
        public void receive(ConsumerRecord<String, String> record) {
            String eventId = eventId(record);
            if (eventId.equals(failOn)) {
                throw new IllegalStateException("still broken");
            }
            if (eventId.equals(blockOn)) {
                inside.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            received.add(record);
        }
    }

    @Test
    void replayOfAGroupWithoutAListenerIsRefusedBeforeClaimingAnything() {
        String key = "K-" + UUID.randomUUID();
        quarantine.quarantine("no-such-group", message(key, "E-1-" + key), new IllegalStateException("boom"));

        assertThatThrownBy(() -> replay.replay("no-such-group", TOPIC, key))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(quarantine.stopped("no-such-group", TOPIC, key)).as("점유하지 않아 그대로 멈춰 있다").isTrue();
    }
}
