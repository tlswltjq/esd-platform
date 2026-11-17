package com.stove.common.messaging.direct;

import static org.assertj.core.api.Assertions.assertThat;

import com.stove.common.event.EventHandler;
import com.stove.common.event.InboundMessage;
import com.stove.common.event.KeyQuarantine;
import com.stove.common.kafka.EventHeaders;
import com.stove.common.messaging.outbox.OutboxEvent;
import com.stove.common.messaging.outbox.OutboxEventRepository;
import com.stove.common.messaging.trace.ReplayTracing;
import com.stove.common.testcontainers.InfraContainers;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 브로커 없음 기준선(결정 203)이 결정 202 의 보장을 지키는지 실제 MySQL 로 본다:
 * 같은 키는 발행 순서대로 한 번에 하나, 늦게 SENT 된 행도 잃지 않음, 제자리 재시도 뒤 격리와 키 정지.
 */
@SpringBootTest(properties = "stove.outbox.relay-enabled=false")
@Import({InfraContainers.MySql.class, InfraContainers.Kafka.class})
class DirectSubscriptionsIntegrationTest {


    @Autowired
    OutboxEventRepository outbox;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    KeyQuarantine quarantine;

    @Autowired
    ApplicationContext context;

    private final Recording handler = new Recording();
    private String group;
    /** 같은 표를 여러 테스트가 쓰므로 토픽을 테스트마다 따로 둔다(앞 테스트의 행을 다시 읽지 않게). */
    private String topic;
    private DirectSubscriptions subscriptions;
    private DirectSubscriptions.Subscription subscription;

    @BeforeEach
    void setUp() {
        group = "fixture-direct-" + UUID.randomUUID();
        topic = "fixture.direct." + UUID.randomUUID().toString().substring(0, 8);
        subscriptions = new DirectSubscriptions(context, jdbc, quarantine, ReplayTracing.NONE,
                Map.of(topic, "outbox_event"), 200, 100, List.of(Duration.ofMillis(10), Duration.ofMillis(10), Duration.ofMillis(10)));
        subscription = new DirectSubscriptions.Subscription(group, topic, "outbox_event", handler, new Class[]{Rejected.class});
    }

    private OutboxEvent row(String key, boolean sent) {
        OutboxEvent event = OutboxEvent.pending(UUID.randomUUID().toString(), "Fixture", key, "FixtureHappened",
                topic, key, "{\"key\":\"" + key + "\"}");
        if (sent) {
            event.markSent();
        }
        return outbox.saveAndFlush(event);
    }

    private List<String> deliveredIds() {
        return handler.received.stream().map(message -> message.header(EventHeaders.EVENT_ID)).toList();
    }

    @Test
    void deliversSentRowsOnceInPublicationOrderAndRemembersWhereItStopped() {
        String key = "K-" + UUID.randomUUID();
        OutboxEvent first = row(key, true);
        OutboxEvent second = row(key, true);
        row(key, false);

        subscriptions.poll(subscription);
        subscriptions.poll(subscription);

        assertThat(deliveredIds()).containsExactly(first.getEventId(), second.getEventId());
        assertThat(handler.received.get(0).key()).isEqualTo(key);
        // payload 는 JSON 열이라 MySQL 이 공백을 정규화한다. 릴레이(Kafka 경로)도 같은 열을 읽으므로 같은 값이다.
        assertThat(handler.received.get(0).payload().replace(" ", "")).isEqualTo("{\"key\":\"" + key + "\"}");
    }

    @Test
    void aRowThatBecomesSentAfterLaterRowsIsStillDelivered() {
        // 앞 id 행이 재시도 대기로 늦게 SENT 되어도(다른 키의 뒤 행이 먼저 나감) 잃지 않는다 — 위치가 id 가 아니라 (sent_at, id) 다.
        OutboxEvent late = row("K-late-" + UUID.randomUUID(), false);
        OutboxEvent early = row("K-early-" + UUID.randomUUID(), true);
        subscriptions.poll(subscription);
        late.markSent();
        outbox.saveAndFlush(late);

        subscriptions.poll(subscription);

        assertThat(deliveredIds()).containsExactly(early.getEventId(), late.getEventId());
    }

    @Test
    void aFailureIsRetriedInPlaceThenQuarantinedAndStopsOnlyItsKey() {
        String broken = "K-broken-" + UUID.randomUUID();
        String healthy = "K-healthy-" + UUID.randomUUID();
        OutboxEvent failing = row(broken, true);
        OutboxEvent behind = row(broken, true);
        OutboxEvent other = row(healthy, true);
        handler.failOn = failing.getEventId();

        subscriptions.poll(subscription);

        assertThat(handler.attempts(failing.getEventId())).as("최초 1회 + 재시도 3회").isEqualTo(4);
        assertThat(quarantine.stopped(group, topic, broken)).isTrue();
        assertThat(deliveredIds()).as("멈춘 키의 뒤 행은 처리하지 않고, 다른 키는 그대로").containsExactly(other.getEventId());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM consumer_quarantine WHERE consumer_group=? AND partition_key=?",
                Integer.class, group, broken)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT event_id FROM consumer_quarantine WHERE consumer_group=? AND failure='KEY_STOPPED'",
                String.class, group)).isEqualTo(behind.getEventId());
    }

    @Test
    void noRetryExceptionsAreQuarantinedOnTheFirstFailure() {
        String key = "K-" + UUID.randomUUID();
        OutboxEvent rejected = row(key, true);
        handler.rejectOn = rejected.getEventId();

        subscriptions.poll(subscription);

        assertThat(handler.attempts(rejected.getEventId())).isEqualTo(1);
        assertThat(quarantine.stopped(group, topic, key)).isTrue();
    }

    static class Rejected extends RuntimeException {
        Rejected() {
            super("rejected");
        }
    }

    static class Recording implements EventHandler {
        final List<InboundMessage> received = new ArrayList<>();
        final List<String> tried = new ArrayList<>();
        volatile String failOn;
        volatile String rejectOn;

        int attempts(String eventId) {
            return (int) tried.stream().filter(eventId::equals).count();
        }

        @Override
        public void receive(InboundMessage message) {
            String eventId = message.header(EventHeaders.EVENT_ID);
            tried.add(eventId);
            if (eventId.equals(failOn)) {
                throw new IllegalStateException("boom");
            }
            if (eventId.equals(rejectOn)) {
                throw new Rejected();
            }
            received.add(message);
        }
    }
}
