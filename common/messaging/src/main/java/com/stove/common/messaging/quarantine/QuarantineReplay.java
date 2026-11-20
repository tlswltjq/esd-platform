package com.stove.common.messaging.quarantine;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.event.EventHandler;
import com.stove.common.event.EventSubscriber;
import com.stove.common.event.InboundMessage;
import com.stove.common.contract.EventHeaders;
import com.stove.common.messaging.trace.ReplayTracing;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 멈춘 키를 원래 순서대로 다시 넣는다(결정 155: 회수는 키 단위, 원래 순서).
 *
 * <p>브로커를 거치지 않고 그 소비 그룹의 {@link EventHandler} 에 직접 넘긴다. 그래서 브로커가 무엇이든 같다(결정 206).
 *
 * <p>한 행씩 처리한다:
 * <ol>
 *   <li>짧은 트랜잭션에서 그 키의 맨 앞 미완료 행을 {@code FOR UPDATE} 로 잠그고 {@code REPLAYING} 으로 점유한다.
 *       다른 재투입이 점유 중이면(점유 기한 안) 멈춘다 — 같은 키의 재투입이 동시에 돌지 않는다(PR #209 R2).</li>
 *   <li>트랜잭션 밖에서 핸들러를 부른다(핸들러는 자기 업무 트랜잭션·Inbox 를 쓴다). 원래 traceparent 안에서 돈다(R4).</li>
 *   <li>짧은 트랜잭션에서 점유가 아직 내 것인지 확인하고(펜싱) {@code REPLAYED}, 실패면 {@code HELD} 로 되돌리고 멈춘다.</li>
 * </ol>
 * 핸들러는 Inbox 로 멱등이라, 점유 기한이 지나 다른 재투입이 같은 행을 다시 처리해도 업무가 두 번 반영되지 않는다.
 */
@Slf4j
public class QuarantineReplay {

    /** 한 번 호출에서 처리할 최대 건수. 계속 쌓이는 키에서 끝나지 않는 것을 막는다(R3). */
    static final int MAX_PER_CALL = 10_000;
    /** 이보다 오래된 점유는 끊긴 재투입으로 보고 회수한다. */
    static final Duration CLAIM_LEASE = Duration.ofMinutes(10);
    private static final TypeReference<Map<String, List<String>>> HEADERS = new TypeReference<>() {
    };

    private final QuarantinedMessageRepository repository;
    private final ApplicationContext beans;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate ownTransaction;
    private final ReplayTracing tracing;
    private final Clock clock;
    private final int maxPerCall;

    public QuarantineReplay(QuarantinedMessageRepository repository, ApplicationContext beans, ObjectMapper objectMapper,
                            PlatformTransactionManager transactionManager, ReplayTracing tracing, Clock clock) {
        this(repository, beans, objectMapper, transactionManager, tracing, clock, MAX_PER_CALL);
    }

    QuarantineReplay(QuarantinedMessageRepository repository, ApplicationContext beans, ObjectMapper objectMapper,
                     PlatformTransactionManager transactionManager, ReplayTracing tracing, Clock clock, int maxPerCall) {
        this.repository = repository;
        this.beans = beans;
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
     * @throws IllegalArgumentException 그 그룹의 핸들러가 이 앱에 없을 때
     */
    public ReplayResult replay(String consumerGroup, String topic, String key) {
        EventHandler handler = handlerOf(consumerGroup);
        String partitionKey = QuarantinedMessage.stopKey(key);
        int replayed = 0;
        while (replayed < maxPerCall) {
            Instant claimedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
            Claim claim = ownTransaction.execute(status -> claimHead(consumerGroup, topic, partitionKey, claimedAt));
            if (claim.message().isEmpty()) {
                return new ReplayResult(replayed, remaining(consumerGroup, topic, partitionKey), claim.busy());
            }
            QuarantinedMessage message = claim.message().get();
            InboundMessage inbound = toInbound(message);
            try {
                tracing.runWithin(inbound.header(EventHeaders.TRACE_PARENT), () -> handler.receive(inbound));
            } catch (RuntimeException failure) {
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

    private InboundMessage toInbound(QuarantinedMessage message) {
        try {
            Map<String, List<String>> headers = objectMapper.readValue(message.getHeaders(), HEADERS);
            return new InboundMessage(message.getTopic(), message.getMessageKey(), message.getPayload(), headers,
                    "quarantine id=" + message.getId());
        } catch (Exception broken) {
            throw new IllegalStateException("보관된 헤더를 읽지 못했다 id=" + message.getId(), broken);
        }
    }

    private EventHandler handlerOf(String consumerGroup) {
        Map<String, EventHandler> byGroup = new HashMap<>();
        for (Map.Entry<String, Object> entry : beans.getBeansWithAnnotation(EventSubscriber.class).entrySet()) {
            EventSubscriber subscription = beans.findAnnotationOnBean(entry.getKey(), EventSubscriber.class);
            if (subscription != null && entry.getValue() instanceof EventHandler handler) {
                byGroup.put(subscription.group(), handler);
            }
        }
        EventHandler handler = byGroup.get(consumerGroup);
        if (handler == null) {
            throw new IllegalArgumentException("이 앱에 그 소비 그룹의 리스너가 없다: " + consumerGroup);
        }
        return handler;
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
