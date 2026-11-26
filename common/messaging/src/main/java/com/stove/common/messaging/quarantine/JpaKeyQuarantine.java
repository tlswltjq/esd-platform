package com.stove.common.messaging.quarantine;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.contract.EventHeaders;
import com.stove.common.messaging.kafka.KeyQuarantine;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 소비 모듈 DB 의 {@code consumer_quarantine} 표로 격리한다(결정 155·160·206).
 *
 * <p>보관은 자기 트랜잭션({@code REQUIRES_NEW})이다. 처리 트랜잭션은 실패로 이미 롤백됐거나(재시도 소진) 아예 없다(정지된 키).
 * 보관이 실패하면 예외가 그대로 나가 오류 처리기가 그 레코드의 오프셋을 넘기지 않는다 — 잃지 않고 다시 받는다.
 */
@Slf4j
public class JpaKeyQuarantine implements KeyQuarantine {

    public static final String RECORDS_METRIC = "stove.consumer.quarantine.records";
    public static final String HELD_METRIC = "stove.consumer.quarantine.held";

    private final QuarantinedMessageRepository repository;
    private final TransactionTemplate ownTransaction;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public JpaKeyQuarantine(QuarantinedMessageRepository repository, PlatformTransactionManager transactionManager,
                            ObjectMapper objectMapper, MeterRegistry meterRegistry) {
        this.repository = repository;
        this.ownTransaction = new TransactionTemplate(transactionManager);
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        Gauge.builder(HELD_METRIC, repository, held -> held.countByStatusIn(QuarantineStatus.STOPPING))
                .description("보관 중인(다시 넣지 않은) 소비 실패 메시지 수. 0이 아니면 멈춘 키가 있다")
                .register(meterRegistry);
    }

    @Override
    public boolean stopped(String consumerGroup, String topic, String key) {
        return repository.existsByConsumerGroupAndTopicAndPartitionKeyAndStatusIn(
                consumerGroup, topic, QuarantinedMessage.stopKey(key), QuarantineStatus.STOPPING);
    }

    @Override
    public void quarantine(String consumerGroup, ConsumerRecord<String, String> record, Throwable failure) {
        String reason = failure == null ? KEY_STOPPED : describe(failure);
        String origin = origin(record);
        QuarantinedMessage held = QuarantinedMessage.held(consumerGroup, record.topic(), record.key(),
                EventHeaders.lastValue(record.headers(), EventHeaders.EVENT_ID),
                EventHeaders.lastValue(record.headers(), EventHeaders.EVENT_TYPE), record.value(),
                headersJson(record), origin, reason);
        ownTransaction.executeWithoutResult(status -> repository.save(held));
        Counter.builder(RECORDS_METRIC)
                .description("격리한 소비 메시지 수. reason=failed 는 처리 실패, key_stopped 는 앞 메시지 때문에 쌓은 것")
                .tag("consumer_group", consumerGroup)
                .tag("reason", failure == null ? "key_stopped" : "failed")
                .register(meterRegistry)
                .increment();
        if (failure == null) {
            log.warn("멈춘 키라 처리하지 않고 쌓았다 group={} topic={} key={} {}",
                    consumerGroup, record.topic(), record.key(), origin);
        } else {
            log.error("소비 실패를 격리하고 키를 멈췄다 group={} topic={} key={} {}",
                    consumerGroup, record.topic(), record.key(), origin, failure);
        }
    }

    /** 보관 행의 {@code origin}. 재투입이 이 값으로 원래 파티션·오프셋을 되살린다({@link QuarantineReplay}). */
    static String origin(ConsumerRecord<String, String> record) {
        return "partition=%d offset=%d".formatted(record.partition(), record.offset());
    }

    /** 헤더 전부(이름 → 값 목록, 받은 순서). 중복 헤더도 잃지 않는다 — 중복을 거부하는 리스너가 있다. */
    private String headersJson(ConsumerRecord<String, String> record) {
        try {
            return objectMapper.writeValueAsString(EventHeaders.all(record.headers()));
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("헤더를 JSON 으로 바꾸지 못했다", impossible);
        }
    }

    private static String describe(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String text = failure.getClass().getSimpleName() + ": " + failure.getMessage();
        return root == failure ? text : text + " (원인 " + root.getClass().getSimpleName() + ": " + root.getMessage() + ")";
    }
}
