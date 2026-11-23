package com.stove.common.messaging.outbox;

import com.stove.common.event.MessagePublisher;
import com.stove.common.event.OutboundMessage;
import com.stove.common.contract.EventHeaders;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Outbox 폴링 릴레이. at-least-once 이고, 발행 단위는 <b>파티션 키</b>다.
 *
 * <p><b>릴레이는 서비스당 1대여야 한다</b> — {@code FOR UPDATE SKIP LOCKED} 가 파티션 키를
 * 모르므로 여러 대면 순서 보장이 조용히 무효가 된다. {@code docs/event-ordering.md} 7절.
 */
@Slf4j
public class OutboxRelay {

    private final OutboxEventRepository repository;
    private final MessagePublisher publisher;
    private final int batchSize;
    private final int maxRetry;
    private final int maxBatchesPerCycle;
    private final OutboxMetrics metrics;
    private final TransactionTemplate transactionTemplate;
    public OutboxRelay(OutboxEventRepository repository, MessagePublisher publisher, int batchSize, int maxRetry,
            int maxBatchesPerCycle, OutboxMetrics metrics, TransactionTemplate transactionTemplate) {
        this.repository = repository;
        this.publisher = publisher;
        this.batchSize = batchSize;
        this.maxRetry = maxRetry;
        this.maxBatchesPerCycle = maxBatchesPerCycle;
        this.metrics = metrics;
        this.transactionTemplate = readCommitted(transactionTemplate);
    }

    /**
     * 릴레이 트랜잭션은 READ COMMITTED다([결정 215], [D-060]). <b>릴레이 전용 템플릿을 넘긴다</b> — 격리 수준을 여기서 맞춘다.
     *
     * <p>배치는 잠근 채 브로커 확인을 기다린다. MySQL 기본값(REPEATABLE READ)이면 {@code FOR UPDATE}가 읽은 범위의
     * 간격까지 잠가, 브로커가 응답하지 않는 동안 다른 주문의 Outbox 삽입이 막히고 커넥션 풀이 고갈된다.
     * 이 잠금의 목적은 집은 행을 다른 트랜잭션이 함께 집지 못하게 하는 것뿐이라 간격 잠금은 필요 없다.
     * 순서 판정({@code lockPendingBatch}의 하위 조회, {@link KeyOrderGuard})은 원래 잠금 없이 커밋된 값을 읽는다.
     * 조립하는 쪽(자동 구성·테스트)이 빠뜨려도 같은 동작이 되도록 생성자가 맞춘다.
     */
    private static TransactionTemplate readCommitted(TransactionTemplate relayOnly) {
        relayOnly.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return relayOnly;
    }

    /** 마지막으로 찍은 {@code sent_at}. 처음에는 표의 최댓값에서 시작한다(스케줄러 한 스레드에서만 쓴다). */
    private Instant lastSentAt;

    /**
     * 폴링 진입점. <b>비울 게 남아 있는 동안은 다음 폴링을 기다리지 않는다.</b>
     * 배치마다 트랜잭션을 따로 연다 — 묶으면 락 보유 시간이 API 응답을 끌어내린다.
     *
     * <p>{@code poll-interval-ms} 는 <b>종단 지연의 바닥</b>이다. 기본값은 200ms다.
     */
    @Scheduled(fixedDelayString = "${stove.outbox.poll-interval-ms:200}")
    public void relay() {
        for (int cycle = 0; cycle < maxBatchesPerCycle; cycle++) {
            int processed = relayOneBatch();
            if (processed < batchSize) {
                return;   // 배치를 다 못 채웠다 = 적체 해소
            }
        }
    }

    private int relayOneBatch() {
        Integer processed = transactionTemplate.execute(status -> {
            long startedAt = System.nanoTime();
            List<OutboxEvent> locked = repository.lockPendingBatch(batchSize);
            if (locked.isEmpty()) {
                return 0;
            }
            List<OutboxEvent> batch = releasableInKeyOrder(locked);
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
     * 같은 키의 앞 미발행 행이 이 배치에 없으면(다른 트랜잭션이 잠가 {@code SKIP LOCKED}로 빠짐) 그 키의 뒤 행은
     * 이번에 보내지 않는다. 걸러진 행은 {@code PENDING} 그대로 다음 폴링에서 다시 판정된다(PR #203 R1).
     *
     * <p>알려진 한계(D-057, 재리뷰 R4 기각): 걸러진 키의 뒤 행이 배치를 가득 채우면 그 잠금이 풀릴 때까지
     * 다른 키도 이 회차에 나가지 못한다. 순서는 지켜지고 잠금이 풀리면 회복한다.
     */
    private List<OutboxEvent> releasableInKeyOrder(List<OutboxEvent> locked) {
        Set<String> keys = new LinkedHashSet<>();
        long maxId = 0;
        for (OutboxEvent event : locked) {
            keys.add(event.getPartitionKey());
            maxId = Math.max(maxId, event.getId());
        }
        List<OutboxKeyPosition> unsent = repository.findUnsentPositions(keys, maxId, OutboxEvent.OutboxStatus.SENT);
        return KeyOrderGuard.releasable(locked, unsent);
    }

    /**
     * 같은 {@code partitionKey} 는 순서대로, 다른 키끼리는 동시에. 웨이브 하나를 통째로 걸어두고
     * 한 번에 기다린 뒤 <b>성공한 키만</b> 다음 웨이브로 넘긴다. [D-013]
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
                    // 브로커 구간 지연의 시작점. ack 뒤 시각(sentAt)과 달리 소비보다 늦을 수 없다([결정 204] B).
                    event.markPublishing(Instant.now());
                    attempts.add(new Attempt(chain, event, publisher.publish(toMessage(event))));
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

    /**
     * 이전에 찍은 값보다 반드시 큰 {@code sent_at}(마이크로초). 브로커 없음 기준선은 {@code (sent_at, id)} 를 읽기 위치로 쓰므로,
     * 시계가 뒤로 가서 새 행이 위치보다 작게 찍히면 영영 읽히지 않는다(PR #210 R2). 시계가 정상이면 지금 시각 그대로다.
     */
    private Instant nextSentAt() {
        if (lastSentAt == null) {
            Instant latest = repository.findLatestSentAt();
            lastSentAt = latest == null ? Instant.EPOCH : latest.truncatedTo(ChronoUnit.MICROS);
        }
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        lastSentAt = now.isAfter(lastSentAt) ? now : lastSentAt.plus(1, ChronoUnit.MICROS);
        return lastSentAt;
    }

    /** @return 발행에 성공했으면 true — 이 키의 다음 이벤트를 보내도 된다는 뜻이다 */
    private boolean awaitAck(OutboxEvent event, CompletableFuture<Void> ack) {
        try {
            ack.get();
            event.markSent(nextSentAt());
            metrics.recordPublished();
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            event.markFailed("relay interrupted", maxRetry);
            metrics.recordFailed(event);
            return false;
        } catch (Exception e) {
            event.markFailed(e.getMessage(), maxRetry);
            metrics.recordFailed(event);
            log.error("outbox 발행 실패 eventId={} type={} retry={}",
                    event.getEventId(), event.getEventType(), event.getRetryCount(), e);
            return false;
        }
    }

    /** 전송에 걸어둔 한 건. 어느 키 체인에 속하는지 함께 들고 있어야 후속 웨이브를 결정할 수 있다. */
    private record Attempt(List<OutboxEvent> chain, OutboxEvent event,
                           CompletableFuture<Void> ack) {
    }

    /**
     * 계약 헤더 셋에 더해 적재 시점의 추적 컨텍스트를 <b>되살린다.</b>
     * 자동 계측에 맡기면 안 되고 브로커 클라이언트의 자동 관측(Kafka {@code spring.kafka.template.observation-enabled})도 꺼야 한다.
     */
    private OutboundMessage toMessage(OutboxEvent event) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(EventHeaders.EVENT_ID, event.getEventId());
        headers.put(EventHeaders.EVENT_TYPE, event.getEventType());
        headers.put(EventHeaders.OCCURRED_AT, event.getCreatedAt().toString());
        if (event.getPublishedAt() != null) {
            headers.put(EventHeaders.PUBLISHED_AT, event.getPublishedAt().toString());
        }
        String traceParent = event.getTraceParent();
        if (traceParent != null && !traceParent.isBlank()) {
            headers.put(EventHeaders.TRACE_PARENT, traceParent);
        }
        return new OutboundMessage(event.getTopic(), event.getPartitionKey(), event.getPayload(), headers);
    }
}
