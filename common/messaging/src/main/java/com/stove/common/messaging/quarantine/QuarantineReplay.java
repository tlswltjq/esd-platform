package com.stove.common.messaging.quarantine;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.event.EventHandler;
import com.stove.common.event.EventSubscriber;
import com.stove.common.event.InboundMessage;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 멈춘 키를 원래 순서대로 다시 넣는다(결정 155: 회수는 키 단위, 원래 순서).
 *
 * <p>브로커를 거치지 않고 그 소비 그룹의 {@link EventHandler} 에 직접 넘긴다. 그래서 브로커가 무엇이든 같다(결정 206).
 * 한 건이 다시 실패하면 거기서 멈추고 키 정지를 유지한다. 다시 넣는 사이 새로 쌓인 메시지도 이어서 처리한다.
 * 처리한 행은 지우지 않고 {@code REPLAYED} 로 남긴다. 핸들러는 Inbox 로 멱등이라 같은 메시지를 두 번 받아도 된다.
 */
@Slf4j
public class QuarantineReplay {

    /** 한 번 호출에서 처리할 최대 건수. 계속 쌓이는 키에서 끝나지 않는 것을 막는다. */
    static final int MAX_PER_CALL = 10_000;
    private static final TypeReference<Map<String, List<String>>> HEADERS = new TypeReference<>() {
    };

    private final QuarantinedMessageRepository repository;
    private final ApplicationContext beans;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate ownTransaction;

    public QuarantineReplay(QuarantinedMessageRepository repository, ApplicationContext beans,
                            ObjectMapper objectMapper, PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.beans = beans;
        this.objectMapper = objectMapper;
        this.ownTransaction = new TransactionTemplate(transactionManager);
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public List<StoppedKey> stoppedKeys() {
        return repository.findStoppedKeys(QuarantineStatus.HELD);
    }

    /**
     * @return 다시 넣은 건수, 남은 건수(0이면 키 정지가 풀렸다), 멈췄다면 그 이유
     * @throws IllegalArgumentException 그 그룹의 핸들러가 이 앱에 없을 때
     */
    public ReplayResult replay(String consumerGroup, String topic, String key) {
        EventHandler handler = handlerOf(consumerGroup);
        String partitionKey = JpaKeyQuarantine.keyOf(key);
        int replayed = 0;
        while (replayed < MAX_PER_CALL) {
            List<QuarantinedMessage> held = repository.findByConsumerGroupAndTopicAndPartitionKeyAndStatusOrderByIdAsc(
                    consumerGroup, topic, partitionKey, QuarantineStatus.HELD);
            if (held.isEmpty()) {
                return new ReplayResult(replayed, 0, null);
            }
            for (QuarantinedMessage message : held) {
                try {
                    handler.receive(toInbound(message));
                } catch (RuntimeException failure) {
                    String reason = "재투입 실패: " + failure.getClass().getSimpleName() + ": " + failure.getMessage();
                    ownTransaction.executeWithoutResult(status -> {
                        message.recordReplayFailure(reason);
                        repository.save(message);
                    });
                    log.error("재투입이 다시 실패해 키 정지를 유지한다 group={} topic={} key={} id={}",
                            consumerGroup, topic, key, message.getId(), failure);
                    return new ReplayResult(replayed, remaining(consumerGroup, topic, partitionKey), reason);
                }
                ownTransaction.executeWithoutResult(status -> {
                    message.markReplayed();
                    repository.save(message);
                });
                replayed++;
            }
        }
        return new ReplayResult(replayed, remaining(consumerGroup, topic, partitionKey), "한 번에 처리할 최대 건수에 닿았다");
    }

    private long remaining(String consumerGroup, String topic, String partitionKey) {
        return repository.findByConsumerGroupAndTopicAndPartitionKeyAndStatusOrderByIdAsc(
                consumerGroup, topic, partitionKey, QuarantineStatus.HELD).size();
    }

    private InboundMessage toInbound(QuarantinedMessage message) {
        try {
            Map<String, List<String>> headers = objectMapper.readValue(message.getHeaders(), HEADERS);
            String key = message.getPartitionKey().isEmpty() ? null : message.getPartitionKey();
            return new InboundMessage(message.getTopic(), key, message.getPayload(), headers,
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

    /**
     * @param replayed 이번에 다시 넣어 처리한 건수
     * @param remaining 아직 보관 중인 건수. 0이면 그 키의 정지가 풀렸다
     * @param stoppedBecause 도중에 멈췄으면 그 이유, 다 끝났으면 null
     */
    public record ReplayResult(int replayed, long remaining, String stoppedBecause) {
    }
}
