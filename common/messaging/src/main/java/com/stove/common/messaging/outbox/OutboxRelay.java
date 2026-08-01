package com.stove.common.messaging.outbox;

import com.stove.common.event.kafka.EventHeaders;
import java.nio.charset.StandardCharsets;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

/**
 * Outbox 폴링 릴레이. PENDING 레코드를 순서대로 Kafka 에 발행하고 상태를 전이시킨다.
 *
 * <p>발행 실패 시 상태를 PENDING 으로 두고 재시도하므로 <b>at-least-once</b> 이다.
 * 중복 수신은 컨슈머 측 {@link com.stove.common.messaging.inbox.ProcessedEventGuard} 로 흡수한다.
 */
@Slf4j
@RequiredArgsConstructor
public class OutboxRelay {

    private final OutboxEventRepository repository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final OutboxProperties properties;

    @Scheduled(fixedDelayString = "${stove.outbox.poll-interval-ms:1000}")
    @Transactional
    public void relay() {
        List<OutboxEvent> batch = repository.lockPendingBatch(properties.batchSize());
        if (batch.isEmpty()) {
            return;
        }
        for (OutboxEvent event : batch) {
            try {
                kafkaTemplate.send(toRecord(event)).get();
                event.markSent();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                event.markFailed("relay interrupted", properties.maxRetry());
                break;
            } catch (Exception e) {
                event.markFailed(e.getMessage(), properties.maxRetry());
                log.error("outbox 발행 실패 eventId={} type={} retry={}",
                        event.getEventId(), event.getEventType(), event.getRetryCount(), e);
            }
        }
        log.debug("outbox relay 처리 {}건", batch.size());
    }

    private ProducerRecord<String, String> toRecord(OutboxEvent event) {
        ProducerRecord<String, String> record =
                new ProducerRecord<>(event.getTopic(), event.getPartitionKey(), event.getPayload());
        record.headers().add(EventHeaders.EVENT_ID, event.getEventId().getBytes(StandardCharsets.UTF_8));
        record.headers().add(EventHeaders.EVENT_TYPE, event.getEventType().getBytes(StandardCharsets.UTF_8));
        record.headers().add(EventHeaders.OCCURRED_AT,
                event.getCreatedAt().toString().getBytes(StandardCharsets.UTF_8));
        return record;
    }
}
