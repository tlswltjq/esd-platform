package com.stove.common.messaging.quarantine;

import static org.assertj.core.api.Assertions.assertThat;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.event.EventHandler;
import com.stove.common.event.EventSubscriber;
import com.stove.common.event.InboundMessage;
import com.stove.common.event.KeyQuarantine;
import com.stove.common.contract.EventHeaders;
import com.stove.common.messaging.quarantine.QuarantineReplay.ReplayResult;
import com.stove.common.messaging.trace.ReplayTracing;
import com.stove.common.testcontainers.InfraContainers;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 결정 155·160·206 — 소비 실패를 소비 모듈 DB 에 보관하고 그 키를 멈추며, 키 단위로 원래 순서대로 다시 넣는다.
 * 실제 MySQL 로 본다(정지 판단과 순서가 SQL 에 달려 있다).
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
    ApplicationContext context;

    @Autowired
    PlatformTransactionManager transactions;

    @BeforeEach
    void reset() {
        handler.received.clear();
        handler.failOn = null;
        handler.blockOn = null;
    }

    private static InboundMessage message(String key, String eventId) {
        return new InboundMessage(TOPIC, key, "{\"n\":\"" + eventId + "\"}",
                Map.of(EventHeaders.EVENT_ID, List.of(eventId), EventHeaders.EVENT_TYPE, List.of("Fixture"),
                        EventHeaders.TRACE_PARENT, List.of("tp-1", "tp-2")), "partition=0 offset=1");
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
        assertThat(handler.received).extracting(m -> m.header(EventHeaders.EVENT_ID))
                .containsExactly("E-1-" + key, "E-2-" + key, "E-3-" + key);
        assertThat(handler.received.get(0).headerValues(EventHeaders.TRACE_PARENT))
                .as("보관된 헤더를 그대로 되살린다(중복 포함)").containsExactly("tp-1", "tp-2");
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
            assertThat(handler.received).extracting(m -> m.header(EventHeaders.EVENT_ID))
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
        QuarantineReplay limited = new QuarantineReplay(repository, context, new ObjectMapper(), transactions,
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
        QuarantineReplay traced = new QuarantineReplay(repository, context, new ObjectMapper(), transactions,
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
        assertThat(handler.received.get(0).header(EventHeaders.EVENT_ID)).isEqualTo(eventId);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Handlers {
        @Bean
        RecordingHandler recordingHandler() {
            return new RecordingHandler();
        }
    }

    @EventSubscriber(topics = TOPIC, group = GROUP)
    static class RecordingHandler implements EventHandler {
        final List<InboundMessage> received = new CopyOnWriteArrayList<>();
        volatile String failOn;
        volatile String blockOn;
        volatile CountDownLatch inside;
        volatile CountDownLatch release;

        @Override
        public void receive(InboundMessage message) {
            String eventId = message.header(EventHeaders.EVENT_ID);
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
            received.add(message);
        }
    }
}
