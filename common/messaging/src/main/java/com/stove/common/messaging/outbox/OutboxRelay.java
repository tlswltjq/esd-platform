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
 * Outbox 폴링 릴레이. PENDING 레코드를 순서대로 Kafka 에 발행하고 상태를 전이시킨다.
 *
 * <p>발행 실패 시 상태를 PENDING 으로 두고 재시도하므로 <b>at-least-once</b> 이다.
 * 중복 수신은 컨슈머 측 {@link com.stove.common.messaging.inbox.ProcessedEventGuard} 로 흡수한다.
 *
 * <p>발행 단위는 <b>파티션 키</b>다 — 같은 애그리거트는 순서대로, 다른 애그리거트는 동시에.
 * 자세한 이유는 {@link #publishPreservingOrder}.
 *
 * <p><b>릴레이는 서비스당 1대여야 한다.</b> {@code lockPendingBatch} 의
 * {@code FOR UPDATE SKIP LOCKED} 는 파티션 키를 모르므로, 릴레이가 여러 대면 같은 키의
 * 이벤트가 서로 다른 릴레이로 갈라져 위의 순서 보장이 조용히 무효가 된다.
 * 다중화하려면 키를 워커에 결정적으로 배정해야 한다({@code MOD(CRC32(partition_key), n)}).
 * 배경은 {@code docs/event-ordering.md} 7절.
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
     *
     * <p>배치가 가득 찼다는 것은 적체가 더 있다는 뜻이다. 그 상태로 고정 주기만큼 쉬면
     * 쉬는 동안 계속 쌓이기만 한다 — 적체가 있을 때 유휴 시간이 처리량의 상한을 만든다.
     *
     * <p>배치마다 트랜잭션을 따로 연다. 여러 배치를 한 트랜잭션으로 묶으면
     * {@code FOR UPDATE SKIP LOCKED} 락 보유 시간이 그만큼 길어져,
     * 같은 커넥션 풀을 쓰는 API 응답까지 끌어내린다.
     *
     * <p><b>{@code poll-interval-ms} 는 한산할 때의 지연을 혼자 정한다.</b> 적체가 있으면 위 루프가
     * 안 쉬고 도니 이 값이 거의 안 걸리지만, 평상시에는 커밋된 이벤트가 다음 폴링까지 그냥 기다린다 —
     * 평균 {@code interval/2}, 최악 {@code interval}. 아무도 릴레이를 깨워주지 않기 때문이고,
     * 그래서 이 값이 <b>종단 지연의 바닥</b>이다.
     *
     * <p>1000 에서 200 으로 낮췄다. 대가인 빈 폴링은 트랜잭션 하나에 인덱스 조회 한 방이라 싸다 —
     * 주기를 5분의 1로 줄였을 때 mysql CPU 가 7.3% → 8.8% 였고 종단 p95 는 1.30s → 0.56s 였다
     * ({@code docs/perf-tuning.md}). 중앙값의 개선폭은 측정 도구가 0.5초마다 확인하는 구조라 못 쟀다.
     *
     * <p><b>여기서 더 낮추려면 상수로는 안 된다 — 적응형 폴링이 다음 수순이다.</b>
     * 0건이면 지수 백오프, 한 건이라도 잡히면 최소 주기로 리셋. 여기에 <b>커밋 후</b> 깨우기를 얹으면
     * 바닥값을 아예 안 낸다. 쓰는 쪽과 릴레이가 같은 JVM 이라 인프로세스 신호로 충분하다
     * ({@code @TransactionalEventListener(AFTER_COMMIT)}). 세 가지를 틀리면 조용히 깨진다.
     * <ul>
     *   <li><b>커밋 전에 깨우면 안 된다</b> — 릴레이가 조회해도 그 행이 아직 안 보인다.
     *       0건 보고 다시 자면서 신호만 소모한다.</li>
     *   <li><b>신호는 큐가 아니라 플래그다</b> — "다시 봐라" 한 번이면 된다.
     *       신호를 쌓으면 그것이 또 하나의 아웃박스가 된다.</li>
     *   <li><b>폴링을 지우면 안 된다</b> — 신호는 지연을 줄이는 최적화고, 정확성을 보증하는 것은 폴링이다.
     *       커밋과 신호 사이에 죽거나 PENDING 이 남은 채로 재기동하면 신호는 오지 않는다.</li>
     * </ul>
     * {@code fixedDelayString} 은 기동 시 한 번 바인딩되어 런타임에 못 바꾸므로,
     * {@code SchedulingConfigurer} + 커스텀 {@code Trigger} 로 갈아야 한다.
     *
     * <p>지금 하지 않는 이유는 200ms 의 실측 대가가 1.5%p 뿐이어서다. 100ms 밑을 노려
     * 한산할 때도 그 비용을 계속 내기 시작하면 그때 값이 생긴다. 판단 근거가 될
     * <b>"0건으로 끝난 회차 수"는 지금 아무도 재지 않는다</b> — {@link OutboxMetrics} 에 한 줄이면 된다.
     * 폴링 자체를 없애는 길은 binlog CDC 이고, 그쪽은 위 클래스 주석의 "릴레이 1대" 제약도 같이 푼다.
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
     * 같은 {@code partitionKey} 는 순서대로, 다른 키끼리는 동시에 발행한다.
     *
     * <p>웨이브 n 은 살아 있는 각 키의 n 번째 이벤트다. 한 웨이브를 통째로 전송에 걸어두고
     * 한 번에 기다린 뒤, <b>성공한 키만</b> 다음 웨이브로 넘어간다.
     *
     * <pre>
     * 웨이브 1: [키A-1, 키B-1, 키C-1]  → 동시 발행, 1회 대기
     * 웨이브 2: [키A-2,        키C-2]  → 1번이 성공한 키만
     * </pre>
     *
     * <p>이 구조가 두 가지를 동시에 해결한다.
     * <ul>
     *   <li><b>순서</b> — 앞 이벤트가 못 나가면 같은 키의 뒤 이벤트도 이번 회차에서 보류된다.
     *       예전에는 실패한 건을 건너뛰고 계속 발행해서 뒤가 앞을 추월했다(D-013).</li>
     *   <li><b>처리량</b> — 대기 횟수가 <i>배치 크기</i>에서 <i>최장 체인 길이</i>로 줄어든다.
     *       파티션 키가 주문번호라 대부분 길이 1 이고, 그러면 배치 전체가 한 번의 대기로 끝난다.</li>
     * </ul>
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
     *
     * <p>보류만으로는 <b>이번 회차</b> 밖에 못 지킨다. 다음 폴링에서 이들만 조회에 잡히면
     * 그대로 추월이 일어난다(D-014). 발행 순서를 지키려면 <b>조회 대상에서 빠지는 시점</b>까지
     * 같이 맞춰야 한다.
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
     * 레코드를 만든다. 계약 헤더 셋에 더해, 적재 시점에 붙잡아 둔 추적 컨텍스트를 <b>되살린다.</b>
     *
     * <p>이 자리에서 헤더를 직접 넣는 것이 요점이다. Kafka 자동 계측에 맡기면 지금 이 스레드
     * — 즉 <b>릴레이 스케줄러</b> — 의 컨텍스트가 실린다. 그러면 컨슈머가 "릴레이가 돈 폴링 회차"를
     * 부모로 삼게 되고, 한 배치에 여러 애그리거트가 섞이면 무관한 주문들이 같은 traceId 를 공유한다.
     * 이어야 할 것은 이벤트를 <b>낳은 요청</b>이므로 저장해 둔 값을 쓴다.
     *
     * <p>그래서 {@code spring.kafka.template.observation-enabled} 는 꺼 둔다(부트 기본값이기도 하다).
     * 켜면 계측이 자기 컨텍스트로 같은 헤더를 덮어써 위의 복원이 무의미해진다.
     *
     * <p>{@code traceParent} 가 없으면 헤더를 붙이지 않는다. 빈 값을 실으면 수신 측 추출이
     * 형식 오류로 다루므로, 없는 편이 낫다 — 그 경우 컨슈머가 <b>새 트레이스를 시작</b>한다.
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
