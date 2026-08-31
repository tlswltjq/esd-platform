package com.stove.common.messaging.outbox;

import com.stove.common.event.kafka.EventHeaders;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Outbox 폴링 릴레이. at-least-once 이고, 발행 단위는 <b>파티션 키</b>다.
 *
 * <p><b>릴레이는 서비스당 1대여야 한다</b> — {@code FOR UPDATE SKIP LOCKED} 가 파티션 키를
 * 모르므로 여러 대면 순서 보장이 조용히 무효가 된다.
 * docs/code-notes.md, {@code docs/event-ordering.md} 7절.
 */
@Slf4j
@RequiredArgsConstructor
public class OutboxRelay {

    private final OutboxEventRepository repository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final OutboxProperties properties;
    private final OutboxMetrics metrics;
    private final TransactionTemplate transactionTemplate;

    /**
     * 폴링 진입점. <b>비울 게 남아 있는 동안은 다음 폴링을 기다리지 않는다.</b>
     * 배치마다 트랜잭션을 따로 연다 — 묶으면 락 보유 시간이 API 응답을 끌어내린다.
     *
     * <p>{@code poll-interval-ms} 는 <b>종단 지연의 바닥</b>이다. 200 으로 정한 근거와
     * 적응형 폴링을 아직 하지 않는 이유는 docs/code-notes.md
     */
    @Scheduled(fixedDelayString = "${stove.outbox.poll-interval-ms:200}")
    public void relay() {
        for (int cycle = 0; cycle < properties.maxBatchesPerCycle(); cycle++) {
            int processed = relayOneBatch();
            if (processed < properties.batchSize()) {
                return;   // 배치를 다 못 채웠다 = 적체 해소
            }
        }
    }

    private int relayOneBatch() {
        Integer processed = transactionTemplate.execute(status -> {
            long startedAt = System.nanoTime();
            List<OutboxEvent> batch = repository.lockPendingBatch(properties.batchSize());
            if (batch.isEmpty()) {
                return 0;
            }
            publishPreservingOrder(batch);

            metrics.recordRelay(System.nanoTime() - startedAt, batch.size());
            log.debug("outbox relay 처리 {}건", batch.size());
            return batch.size();
        });
        return processed == null ? 0 : processed;
    }

    /**
     * 같은 {@code partitionKey} 는 순서대로, 다른 키끼리는 동시에. 웨이브 하나를 통째로 걸어두고
     * 한 번에 기다린 뒤 <b>성공한 키만</b> 다음 웨이브로 넘긴다. [D-013] docs/code-notes.md
     */
    private void publishPreservingOrder(List<OutboxEvent> batch) {
        List<List<OutboxEvent>> chains = new ArrayList<>(batch.stream()
                .collect(Collectors.groupingBy(OutboxEvent::getPartitionKey,
                        LinkedHashMap::new, Collectors.toList()))
                .values());

        for (int wave = 0; !chains.isEmpty(); wave++) {
            List<Attempt> attempts = new ArrayList<>();
            for (List<OutboxEvent> chain : chains) {
                if (wave < chain.size()) {
                    OutboxEvent event = chain.get(wave);
                    attempts.add(new Attempt(chain, event, kafkaTemplate.send(toRecord(event))));
                }
            }
            if (attempts.isEmpty()) {
                return;
            }

            List<List<OutboxEvent>> survivors = new ArrayList<>();
            for (Attempt attempt : attempts) {
                if (awaitAck(attempt.event(), attempt.ack())) {
                    survivors.add(attempt.chain());
                } else {
                    holdRemainder(attempt.chain(), wave, attempt.event().getNextAttemptAt());
                    if (Thread.currentThread().isInterrupted()) {
                        return;
                    }
                }
            }
            chains = survivors;
        }
    }

    /**
     * 실패한 이벤트 뒤에 남은 같은 키의 이벤트를 앞의 재시도 시각까지 함께 미룬다.
     * <b>조회 대상에서 빠지는 시점까지 맞춰야 한다.</b> [D-014]
     */
    private void holdRemainder(List<OutboxEvent> chain, int failedWave, Instant until) {
        for (int i = failedWave + 1; i < chain.size(); i++) {
            chain.get(i).holdUntil(until);
        }
    }

    /** @return 발행에 성공했으면 true — 이 키의 다음 이벤트를 보내도 된다는 뜻이다 */
    private boolean awaitAck(OutboxEvent event, CompletableFuture<SendResult<String, String>> ack) {
        try {
            ack.get();
            event.markSent();
            metrics.recordPublished();
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            event.markFailed("relay interrupted", properties.maxRetry());
            metrics.recordFailed(event);
            return false;
        } catch (Exception e) {
            event.markFailed(e.getMessage(), properties.maxRetry());
            metrics.recordFailed(event);
            log.error("outbox 발행 실패 eventId={} type={} retry={}",
                    event.getEventId(), event.getEventType(), event.getRetryCount(), e);
            return false;
        }
    }

    /** 전송에 걸어둔 한 건. 어느 키 체인에 속하는지 함께 들고 있어야 후속 웨이브를 결정할 수 있다. */
    private record Attempt(List<OutboxEvent> chain, OutboxEvent event,
                           CompletableFuture<SendResult<String, String>> ack) {
    }

    /**
     * 계약 헤더 셋에 더해 적재 시점의 추적 컨텍스트를 <b>되살린다.</b>
     * 자동 계측에 맡기면 안 되고 {@code spring.kafka.template.observation-enabled} 도 꺼야 한다 —
     * 근거는 docs/code-notes.md
     */
    private ProducerRecord<String, String> toRecord(OutboxEvent event) {
        ProducerRecord<String, String> record =
                new ProducerRecord<>(event.getTopic(), event.getPartitionKey(), event.getPayload());
        record.headers().add(EventHeaders.EVENT_ID, event.getEventId().getBytes(StandardCharsets.UTF_8));
        record.headers().add(EventHeaders.EVENT_TYPE, event.getEventType().getBytes(StandardCharsets.UTF_8));
        record.headers().add(EventHeaders.OCCURRED_AT,
                event.getCreatedAt().toString().getBytes(StandardCharsets.UTF_8));
        String traceParent = event.getTraceParent();
        if (traceParent != null && !traceParent.isBlank()) {
            record.headers().add(EventHeaders.TRACE_PARENT, traceParent.getBytes(StandardCharsets.UTF_8));
        }
        return record;
    }
}
