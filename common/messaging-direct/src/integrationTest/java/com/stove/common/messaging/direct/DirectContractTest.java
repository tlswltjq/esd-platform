package com.stove.common.messaging.direct;

import com.stove.common.contract.EventHeaders;
import com.stove.common.event.EventHandler;
import com.stove.common.event.KeyQuarantine;
import com.stove.common.event.OutboundMessage;
import com.stove.common.messaging.contract.MessagingContractTest;
import com.stove.common.messaging.delivery.SubscribedHandler;
import com.stove.common.messaging.outbox.OutboxEvent;
import com.stove.common.messaging.outbox.OutboxEventRepository;
import com.stove.common.messaging.trace.ReplayTracing;
import com.stove.common.testcontainers.InfraContainers;
import java.time.Duration;
import java.util.Optional;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.containers.ContainerState;
import org.testcontainers.containers.MySQLContainer;

/**
 * 브로커 없음 기준선이 공통 계약(결정 202·206)을 지키는지 실제 MySQL 로 본다(결정 209).
 * 이 기준선에서 "발행"은 릴레이가 Outbox 행을 {@code SENT} 로 표시하는 것이다(보낼 곳이 없다) — 그래서 {@code SENT} 행을 적는다.
 */
@SpringBootTest(properties = "stove.outbox.relay-enabled=false")
@Import(InfraContainers.MySql.class)
class DirectContractTest extends MessagingContractTest {

    private static final List<Duration> SHORT_RETRIES = List.of(Duration.ofMillis(10), Duration.ofMillis(10), Duration.ofMillis(10));

    @Autowired
    OutboxEventRepository outbox;

    /** S3(결정 222): 브로커 없음은 Outbox 를 담는 공유 MySQL 이 저장소다. 측정 때 {@code SharedContainers} 가 메모리 1GB 를 건다. */
    @Autowired
    MySQLContainer<?> mysqlContainer;

    @Override
    protected Optional<ContainerState> brokerContainer() {
        return Optional.of(mysqlContainer);
    }

    @Override
    protected String brokerDataDir() {
        return "/var/lib/mysql";
    }

    @Override
    protected AutoCloseable subscribe(String group, String topic, KeyQuarantine storage, EventHandler handler,
                                      List<Class<? extends RuntimeException>> noRetryFor) {
        DirectSubscriptions subscriptions = new DirectSubscriptions(
                () -> List.of(new SubscribedHandler(group, topic, handler, asArray(noRetryFor))), jdbc, storage,
                ReplayTracing.NONE, Map.of(topic, "outbox_event"), 20, 100, retryDelays(SHORT_RETRIES));
        subscriptions.start();
        return subscriptions::stop;
    }

    /** (그룹, 토픽)마다 커서 하나라 모든 키가 같은 분할이다. 다른 분할 대조군은 없다. */
    @Override
    protected Optional<String> keyInSamePartition(String key) {
        return keyWhere(candidate -> !candidate.equals(key));
    }

    @Override
    protected void publish(OutboundMessage message) {
        OutboxEvent event = OutboxEvent.pending(message.headers().get(EventHeaders.EVENT_ID), "Contract", message.key(),
                message.headers().get(EventHeaders.EVENT_TYPE), message.topic(), message.key(), message.payload());
        event.markSent();
        outbox.saveAndFlush(event);
    }
}
