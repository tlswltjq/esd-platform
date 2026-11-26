package com.stove.common.messaging.quarantine;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.contract.EventHeaders;
import com.stove.common.messaging.trace.ReplayTracing;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.AcknowledgingConsumerAwareMessageListener;
import org.springframework.kafka.listener.AcknowledgingMessageListener;
import org.springframework.kafka.listener.ConsumerAwareMessageListener;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.listener.ListenerUtils;
import org.springframework.kafka.listener.MessageListener;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 멈춘 키를 원래 순서대로 다시 넣는다(결정 155: 회수는 키 단위, 원래 순서).
 *
 * <p>브로커를 거치지 않고, 그 (소비 그룹, 토픽)을 받는 {@code @KafkaListener} 컨테이너의 리스너에 보관 행으로 다시 만든
 * {@link ConsumerRecord} 를 직접 넘긴다. 그래서 운영 수신과 같은 경로({@code *Mapper.from} → 업무 서비스·Inbox)를 탄다.
 * 컨테이너의 멈춘 키 인터셉터·오류 처리기는 거치지 않는다 — 재투입 대상이 바로 멈춘 키이고, 실패는 아래 3번이 다룬다.
 *
 * <p>한 행씩 처리한다:
 * <ol>
 *   <li>짧은 트랜잭션에서 그 키의 맨 앞 미완료 행을 {@code FOR UPDATE} 로 잠그고 {@code REPLAYING} 으로 점유한다.
 *       다른 재투입이 점유 중이면(점유 기한 안) 멈춘다 — 같은 키의 재투입이 동시에 돌지 않는다(PR #209 R2).</li>
 *   <li>트랜잭션 밖에서 리스너를 부른다(리스너는 자기 업무 트랜잭션·Inbox 를 쓴다). 원래 traceparent 안에서 돈다(R4).</li>
 *   <li>짧은 트랜잭션에서 점유가 아직 내 것인지 확인하고(펜싱) {@code REPLAYED}, 실패면 {@code HELD} 로 되돌리고 멈춘다.</li>
 * </ol>
 * 리스너는 Inbox 로 멱등이라, 점유 기한이 지나 다른 재투입이 같은 행을 다시 처리해도 업무가 두 번 반영되지 않는다.
 */
@Slf4j
public class QuarantineReplay {

    /** 한 번 호출에서 처리할 최대 건수. 계속 쌓이는 키에서 끝나지 않는 것을 막는다(R3). */
    static final int MAX_PER_CALL = 10_000;
    /** 이보다 오래된 점유는 끊긴 재투입으로 보고 회수한다. */
    static final Duration CLAIM_LEASE = Duration.ofMinutes(10);
    private static final TypeReference<Map<String, List<String>>> HEADERS = new TypeReference<>() {
    };
    /** 보관 행의 {@code origin}({@code JpaKeyQuarantine#origin}) 형식. */
    private static final Pattern ORIGIN = Pattern.compile("partition=(-?\\d+) offset=(-?\\d+)");

    private final QuarantinedMessageRepository repository;
    private final KafkaListenerEndpointRegistry listeners;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate ownTransaction;
    private final ReplayTracing tracing;
    private final Clock clock;
    private final int maxPerCall;

    public QuarantineReplay(QuarantinedMessageRepository repository, KafkaListenerEndpointRegistry listeners, ObjectMapper objectMapper,
                            PlatformTransactionManager transactionManager, ReplayTracing tracing, Clock clock) {
        this(repository, listeners, objectMapper, transactionManager, tracing, clock, MAX_PER_CALL);
    }

    QuarantineReplay(QuarantinedMessageRepository repository, KafkaListenerEndpointRegistry listeners, ObjectMapper objectMapper,
                     PlatformTransactionManager transactionManager, ReplayTracing tracing, Clock clock, int maxPerCall) {
        this.repository = repository;
        this.listeners = listeners;
        this.objectMapper = objectMapper;
        this.ownTransaction = new TransactionTemplate(transactionManager);
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.tracing = tracing;
        this.clock = clock;
        this.maxPerCall = maxPerCall;
    }

    public List<StoppedKey> stoppedKeys() {
        return repository.findStoppedKeys(QuarantineStatus.STOPPING);
    }

    /**
     * @return 다시 넣은 건수, 남은 건수(0이면 키 정지가 풀렸다), 멈췄다면 그 이유
     * @throws IllegalArgumentException 그 그룹·토픽을 받는 리스너가 이 앱에 없을 때
     */
    public ReplayResult replay(String consumerGroup, String topic, String key) {
        Object listener = listenerOf(consumerGroup, topic);
        String partitionKey = QuarantinedMessage.stopKey(key);
        int replayed = 0;
        while (replayed < maxPerCall) {
            Instant claimedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
            Claim claim = ownTransaction.execute(status -> claimHead(consumerGroup, topic, partitionKey, claimedAt));
            if (claim.message().isEmpty()) {
                return new ReplayResult(replayed, remaining(consumerGroup, topic, partitionKey), claim.busy());
            }
            QuarantinedMessage message = claim.message().get();
            ConsumerRecord<String, String> record = toRecord(message);
            try {
                tracing.runWithin(EventHeaders.lastValue(record.headers(), EventHeaders.TRACE_PARENT),
                        () -> deliver(listener, record));
            } catch (RuntimeException thrown) {
                Throwable failure = unwrap(thrown);
                String reason = "재투입 실패: " + failure.getClass().getSimpleName() + ": " + failure.getMessage();
                ownTransaction.executeWithoutResult(status -> finish(message.getId(), claimedAt, current -> current.release(reason)));
                log.error("재투입이 다시 실패해 키 정지를 유지한다 group={} topic={} key={} id={}",
                        consumerGroup, topic, key, message.getId(), failure);
                return new ReplayResult(replayed, remaining(consumerGroup, topic, partitionKey), reason);
            }
            boolean mine = Boolean.TRUE.equals(ownTransaction.execute(
                    status -> finish(message.getId(), claimedAt, QuarantinedMessage::markReplayed)));
            if (!mine) {
                return new ReplayResult(replayed, remaining(consumerGroup, topic, partitionKey),
                        "점유 기한이 지나 다른 재투입이 이 키를 이어받았다");
            }
            replayed++;
        }
        return new ReplayResult(replayed, remaining(consumerGroup, topic, partitionKey), "한 번에 처리할 최대 건수에 닿았다");
    }

    /** 맨 앞 미완료 행을 잠그고 점유한다. 다른 재투입이 기한 안에 점유 중이면 비어 있는 결과와 이유. */
    private Claim claimHead(String consumerGroup, String topic, String partitionKey, Instant claimedAt) {
        Optional<QuarantinedMessage> head = repository.lockHead(consumerGroup, topic, partitionKey);
        if (head.isEmpty()) {
            return new Claim(Optional.empty(), null);
        }
        QuarantinedMessage message = head.get();
        if (message.getStatus() == QuarantineStatus.REPLAYING
                && message.getClaimedAt().plus(CLAIM_LEASE).isAfter(claimedAt)) {
            return new Claim(Optional.empty(), "다른 재투입이 이 키를 처리하고 있다");
        }
        message.claim(claimedAt);
        repository.save(message);
        return new Claim(Optional.of(message), null);
    }

    /** 점유가 아직 내 것일 때만 상태를 바꾼다(펜싱). */
    private boolean finish(Long id, Instant claimedAt, Consumer<QuarantinedMessage> change) {
        QuarantinedMessage current = repository.findById(id).orElseThrow();
        if (!current.claimedBy(claimedAt)) {
            return false;
        }
        change.accept(current);
        repository.save(current);
        return true;
    }

    private long remaining(String consumerGroup, String topic, String partitionKey) {
        return repository.countByConsumerGroupAndTopicAndPartitionKeyAndStatusIn(
                consumerGroup, topic, partitionKey, QuarantineStatus.STOPPING);
    }

    /** 보관 행으로 원래 레코드를 다시 만든다: 토픽·파티션·오프셋·키·본문·헤더(이름마다 받은 순서, 중복 포함). */
    private ConsumerRecord<String, String> toRecord(QuarantinedMessage message) {
        Map<String, List<String>> headers;
        try {
            headers = objectMapper.readValue(message.getHeaders(), HEADERS);
        } catch (Exception broken) {
            throw new IllegalStateException("보관된 헤더를 읽지 못했다 id=" + message.getId(), broken);
        }
        Matcher origin = ORIGIN.matcher(message.getOrigin());
        // 파티션·오프셋은 오류 메시지의 위치로만 쓴다. 형식이 다르면(예전 행) 알 수 없음(-1)으로 둔다.
        int partition = origin.matches() ? Integer.parseInt(origin.group(1)) : -1;
        long offset = origin.matches() ? Long.parseLong(origin.group(2)) : -1L;
        ConsumerRecord<String, String> record = new ConsumerRecord<>(message.getTopic(), partition, offset,
                message.getMessageKey(), message.getPayload());
        headers.forEach((name, values) -> values.forEach(value ->
                record.headers().add(name, value.getBytes(StandardCharsets.UTF_8))));
        return record;
    }

    /**
     * 그 (소비 그룹, 토픽)을 받는 리스너 컨테이너의 리스너. 같은 그룹이 토픽마다 다른 리스너를 둘 수 있어 토픽까지 맞춘다.
     * 기동 뒤에 찾는다 — 컨테이너는 컨텍스트가 다 뜬 뒤 레지스트리에 모인다.
     */
    private Object listenerOf(String consumerGroup, String topic) {
        List<Object> found = new ArrayList<>();
        for (MessageListenerContainer container : listeners.getListenerContainers()) {
            String[] topics = container.getContainerProperties().getTopics();
            if (consumerGroup.equals(container.getGroupId()) && topics != null && Arrays.asList(topics).contains(topic)) {
                found.add(container.getContainerProperties().getMessageListener());
            }
        }
        if (found.isEmpty()) {
            throw new IllegalArgumentException("이 앱에 그 소비 그룹·토픽의 리스너가 없다: " + consumerGroup + " " + topic);
        }
        if (found.size() > 1) {
            throw new IllegalStateException("그 소비 그룹·토픽의 리스너가 여럿이라 고를 수 없다: " + consumerGroup + " " + topic);
        }
        return found.get(0);
    }

    /** 컨테이너가 리스너를 부르는 방식(리스너 종류별)과 같이 부른다. 수동 ack·소비자는 없다. */
    @SuppressWarnings("unchecked")
    private static void deliver(Object listener, ConsumerRecord<String, String> record) {
        switch (ListenerUtils.determineListenerType(listener)) {
            case ACKNOWLEDGING_CONSUMER_AWARE ->
                    ((AcknowledgingConsumerAwareMessageListener<String, String>) listener).onMessage(record, null, null);
            case CONSUMER_AWARE -> ((ConsumerAwareMessageListener<String, String>) listener).onMessage(record,
                    (org.apache.kafka.clients.consumer.Consumer<?, ?>) null); // java.util.function.Consumer 와 이름이 겹친다
            case ACKNOWLEDGING -> ((AcknowledgingMessageListener<String, String>) listener).onMessage(record, (Acknowledgment) null);
            case SIMPLE -> ((MessageListener<String, String>) listener).onMessage(record);
        }
    }

    /** {@code @KafkaListener} 어댑터는 리스너 예외를 {@link ListenerExecutionFailedException} 으로 감싼다. 사유에는 원래 예외를 남긴다. */
    private static Throwable unwrap(RuntimeException thrown) {
        return thrown instanceof ListenerExecutionFailedException && thrown.getCause() != null ? thrown.getCause() : thrown;
    }

    private record Claim(Optional<QuarantinedMessage> message, String busy) {
    }

    /**
     * @param replayed 이번에 다시 넣어 처리한 건수
     * @param remaining 아직 멈춘 상태(보관·점유 중)인 건수. 0이면 그 키의 정지가 풀렸다
     * @param stoppedBecause 도중에 멈췄으면 그 이유, 다 끝났으면 null
     */
    public record ReplayResult(int replayed, long remaining, String stoppedBecause) {
    }
}
