package com.stove.common.messaging.quarantine;

import static org.assertj.core.api.Assertions.assertThat;

import com.stove.common.event.EventHandler;
import com.stove.common.event.EventSubscriber;
import com.stove.common.event.InboundMessage;
import com.stove.common.event.KeyQuarantine;
import com.stove.common.kafka.EventHeaders;
import com.stove.common.messaging.quarantine.QuarantineReplay.ReplayResult;
import com.stove.common.testcontainers.InfraContainers;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

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

    @BeforeEach
    void reset() {
        handler.received.clear();
        handler.failOn = null;
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

    @TestConfiguration(proxyBeanMethods = false)
    static class Handlers {
        @Bean
        RecordingHandler recordingHandler() {
            return new RecordingHandler();
        }
    }

    @EventSubscriber(topics = TOPIC, group = GROUP)
    static class RecordingHandler implements EventHandler {
        final List<InboundMessage> received = new ArrayList<>();
        volatile String failOn;

        @Override
        public void receive(InboundMessage message) {
            if (message.header(EventHeaders.EVENT_ID).equals(failOn)) {
                throw new IllegalStateException("still broken");
            }
            received.add(message);
        }
    }
}
