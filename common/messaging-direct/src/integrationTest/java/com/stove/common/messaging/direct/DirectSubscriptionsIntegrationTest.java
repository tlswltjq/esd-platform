package com.stove.common.messaging.direct;

import static org.assertj.core.api.Assertions.assertThat;

import com.stove.common.event.EventHandler;
import com.stove.common.event.InboundMessage;
import com.stove.common.event.KeyQuarantine;
import com.stove.common.contract.EventHeaders;
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
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 브로커 없음 기준선(결정 203)이 결정 202 의 보장을 지키는지 실제 MySQL 로 본다:
 * 같은 키는 발행 순서대로 한 번에 하나, 늦게 SENT 된 행도 잃지 않음, 제자리 재시도 뒤 격리와 키 정지.
 */
@SpringBootTest(properties = "stove.outbox.relay-enabled=false")
@Import(InfraContainers.MySql.class)
class DirectSubscriptionsIntegrationTest {


    @Autowired
    OutboxEventRepository outbox;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    KeyQuarantine quarantine;

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
        subscriptions = new DirectSubscriptions(List::of, jdbc, quarantine, ReplayTracing.NONE,
                Map.of(topic, "outbox_event"), 200, 100, List.of(Duration.ofMillis(10), Duration.ofMillis(10), Duration.ofMillis(10)));
        subscription = new DirectSubscriptions.Subscription(group, topic, "outbox_event", handler, new Class[0]);
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

    private DirectSubscriptions anotherInstance() {
        return new DirectSubscriptions(List::of, jdbc, quarantine, ReplayTracing.NONE,
                Map.of(topic, "outbox_event"), 200, 100, List.of(Duration.ofMillis(10)));
    }

    @Test
    void anotherInstanceOfTheSameGroupDoesNotReadWhileTheLeaseIsHeld() {
        OutboxEvent first = row("K-" + UUID.randomUUID(), true);
        Recording other = new Recording();
        subscriptions.poll(subscription);
        OutboxEvent second = row("K-" + UUID.randomUUID(), true);

        anotherInstance().poll(new DirectSubscriptions.Subscription(group, topic, "outbox_event", other, new Class[0]));
        subscriptions.poll(subscription);

        assertThat(other.received).as("점유 중인 인스턴스가 있으면 같은 그룹의 다른 인스턴스는 읽지 않는다").isEmpty();
        assertThat(deliveredIds()).containsExactly(first.getEventId(), second.getEventId());
    }

    @Test
    void anExpiredLeaseIsTakenOverAndTheFormerOwnerCannotMoveTheCursor() {
        String key = "K-" + UUID.randomUUID();
        OutboxEvent first = row(key, true);
        OutboxEvent second = row(key, true);
        DirectSubscriptions successor = anotherInstance();
        Recording next = new Recording();
        DirectSubscriptions.Subscription successorSubscription =
                new DirectSubscriptions.Subscription(group, topic, "outbox_event", next, new Class[0]);
        // 첫 행을 처리하는 동안 점유 기한이 지나 다른 인스턴스가 이어받는다.
        handler.onReceive = () -> {
            jdbc.update("UPDATE direct_cursor SET lease_until = NOW(6) - INTERVAL 1 SECOND WHERE consumer_group = ?", group);
            successor.poll(successorSubscription);
        };

        subscriptions.poll(subscription);

        assertThat(deliveredIds()).as("점유를 잃은 쪽은 위치를 넘기지 못하고 멈춘다").containsExactly(first.getEventId());
        assertThat(next.received.stream().map(message -> message.header(EventHeaders.EVENT_ID)).toList())
                .as("이어받은 쪽은 저장된 위치부터 읽는다(앞 행은 Inbox 가 중복을 막는다)")
                .containsExactly(first.getEventId(), second.getEventId());
        assertThat(jdbc.queryForObject("SELECT row_id FROM direct_cursor WHERE consumer_group = ?", Long.class, group))
                .as("늦게 끝난 옛 점유자가 위치를 되돌리지 않는다").isEqualTo(second.getId());
    }

    static class Recording implements EventHandler {
        final List<InboundMessage> received = new ArrayList<>();
        /** 첫 수신 때 한 번 실행한다(처리 도중의 상황을 만든다). */
        volatile Runnable onReceive;

        @Override
        public void receive(InboundMessage message) {
            received.add(message);
            Runnable once = onReceive;
            if (once != null) {
                onReceive = null;
                once.run();
            }
        }
    }
}
