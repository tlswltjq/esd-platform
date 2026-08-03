package com.stove.common.messaging.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.stove.common.event.EventType;
import com.stove.common.event.Topics;
import com.stove.common.event.kafka.EventHeaders;
import com.stove.common.messaging.outbox.OutboxEvent.OutboxStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Outbox 릴레이의 실동작. 9개 서비스 전부가 이 배달원에 의존하는데
 * 지금까지 검증은 ArchUnit(파일 위치) 하나뿐이었다.
 *
 * <p>Kafka 를 띄우지 않는다. 검증 대상은 브로커 구현이 아니라
 * <b>실패했을 때 릴레이가 무엇을 하는가</b>이므로 {@link KafkaTemplate} 을 대역으로 세우고
 * 폴링 주기를 {@code relay()} 호출 횟수로 모사한다.
 */
class OutboxRelayTest {

    private static final int MAX_RETRY = 3;
    private static final int BATCH_SIZE = 100;
    private static final int MAX_BATCHES_PER_CYCLE = 10;

    /** 트랜잭션 매니저 없이 콜백만 실행한다 — 검증 대상은 발행 로직이지 트랜잭션이 아니다. */
    private static TransactionTemplate directTransactionTemplate() {
        return new TransactionTemplate() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                return action.doInTransaction(new SimpleTransactionStatus());
            }
        };
    }

    private final OutboxEventRepository repository = mock(OutboxEventRepository.class);
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);

    private final List<OutboxEvent> store = new ArrayList<>();
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private OutboxRelay relay;

    /**
     * 폴링 시각. 실제 쿼리의 {@code NOW(6)} 자리다.
     *
     * <p>테스트가 직접 쥐고 있어야 "백오프가 아직 안 풀린 회차"를 결정적으로 만들 수 있다.
     * 시스템 시각에 기대면 1초짜리 백오프를 기다리는 테스트가 되어 느리고 불안정해진다.
     */
    private Instant pollingAt;

    @BeforeEach
    void setUp() {
        pollingAt = Instant.now();

        // 실제 lockPendingBatch 와 같은 조건으로 집어준다 — PENDING 이고,
        // next_attempt_at 이 없거나 이미 지난 것만. id 순은 store 의 삽입 순서다.
        //
        // 시간 필터를 모사하지 않으면 "앞 이벤트는 백오프로 안 잡히는데 뒤 이벤트는 잡히는"
        // 회차 간 상태를 표현할 수 없다. 순서 보장(여기)과 시간 필터(OutboxBackOffQueryTest)를
        // 서로 다른 층에 두면 정확히 그 교집합이 사각이 된다 — D-014 가 거기서 나왔다.
        when(repository.lockPendingBatch(anyInt())).thenAnswer(invocation -> store.stream()
                .filter(event -> event.getStatus() == OutboxStatus.PENDING)
                .filter(this::attemptDue)
                .limit(invocation.<Integer>getArgument(0))
                .toList());

        relay = new OutboxRelay(repository, kafkaTemplate,
                new OutboxProperties(true, BATCH_SIZE, 1000L, MAX_RETRY, MAX_BATCHES_PER_CYCLE),
                new OutboxMetrics(meterRegistry, repository),
                directTransactionTemplate());
    }

    private double counter(String name) {
        return meterRegistry.get(name).counter().count();
    }

    /** {@code next_attempt_at IS NULL OR next_attempt_at <= NOW(6)} 와 같은 판정. */
    private boolean attemptDue(OutboxEvent event) {
        return event.getNextAttemptAt() == null || !event.getNextAttemptAt().isAfter(pollingAt);
    }

    /**
     * 백오프가 전부 풀린 뒤의 폴링 1회.
     *
     * <p>재시도 자체를 검증하는 테스트용이다. 백오프 상한(5분)을 넘겨 시계를 밀므로
     * 실패했던 이벤트가 반드시 다시 잡힌다.
     */
    private void pollAfterBackOff() {
        pollingAt = pollingAt.plus(Duration.ofMinutes(10));
        relay.relay();
    }

    private OutboxEvent record(String eventId) {
        return recordWithKey(eventId, "ORD-1");
    }

    private OutboxEvent recordWithKey(String eventId, String partitionKey) {
        OutboxEvent event = OutboxEvent.pending(eventId, "Payment", partitionKey,
                EventType.PAYMENT_COMPLETED, Topics.PAYMENT, partitionKey,
                "{\"orderNo\":\"" + partitionKey + "\"}");
        store.add(event);
        return event;
    }

    @SuppressWarnings("unchecked")
    private void brokerHealthy() {
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
    }

    @SuppressWarnings("unchecked")
    private void brokerDown() {
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(
                        new IllegalStateException("Broker not available")));
    }

    @Test
    @DisplayName("발행에 성공하면 SENT 로 전이한다")
    void publishesPendingEvent() {
        OutboxEvent event = record("EVT-1");
        brokerHealthy();

        relay.relay();

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.SENT);
        assertThat(event.getSentAt()).isNotNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("토픽·파티션 키·계약 헤더 3종을 그대로 실어 보낸다")
    void carriesContractHeaders() {
        OutboxEvent event = record("EVT-1");
        brokerHealthy();

        relay.relay();

        ArgumentCaptor<ProducerRecord<String, String>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());
        ProducerRecord<String, String> sent = captor.getValue();

        assertThat(sent.topic()).isEqualTo(Topics.PAYMENT);
        assertThat(sent.key()).isEqualTo("ORD-1");
        assertThat(header(sent, EventHeaders.EVENT_ID)).isEqualTo("EVT-1");
        assertThat(header(sent, EventHeaders.EVENT_TYPE)).isEqualTo(EventType.PAYMENT_COMPLETED);
        assertThat(header(sent, EventHeaders.OCCURRED_AT)).isEqualTo(event.getCreatedAt().toString());
    }

    @Test
    @DisplayName("이미 발행한 이벤트는 다시 집지 않는다")
    void doesNotRepublishSentEvent() {
        record("EVT-1");
        brokerHealthy();

        relay.relay();
        relay.relay();

        verify(kafkaTemplate, times(1)).send(any(ProducerRecord.class));
    }

    @Test
    @DisplayName("발행 실패는 PENDING 을 유지해 다음 폴링에서 재시도된다")
    void failureKeepsEventPending() {
        OutboxEvent event = record("EVT-1");
        brokerDown();

        relay.relay();

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(event.getRetryCount()).isEqualTo(1);
        assertThat(event.getLastError()).contains("Broker not available");
    }

    @Test
    @DisplayName("일시적 장애는 복구 후 정상 발행된다 — at-least-once 의 핵심")
    void recoversAfterTransientOutage() {
        OutboxEvent event = record("EVT-1");

        brokerDown();
        relay.relay();
        pollAfterBackOff();
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);

        brokerHealthy();
        pollAfterBackOff();

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.SENT);
    }

    @Test
    @DisplayName("한 애그리거트의 실패가 배치 전체를 막지 않는다")
    void oneFailureDoesNotBlockBatch() {
        // 서로 다른 주문이다. 순서 보장은 같은 키 안에서만 필요하므로
        // 키가 다르면 실패 여부와 무관하게 계속 흘러야 한다.
        OutboxEvent first = recordWithKey("EVT-1", "ORD-1");
        OutboxEvent second = recordWithKey("EVT-2", "ORD-2");
        OutboxEvent third = recordWithKey("EVT-3", "ORD-3");

        when(kafkaTemplate.send(any(ProducerRecord.class))).thenAnswer(invocation -> {
            ProducerRecord<String, String> sent = invocation.getArgument(0);
            return "ORD-2".equals(sent.key())
                    ? CompletableFuture.failedFuture(new IllegalStateException("record too large"))
                    : CompletableFuture.completedFuture(mock(SendResult.class));
        });

        relay.relay();

        assertThat(first.getStatus()).isEqualTo(OutboxStatus.SENT);
        assertThat(second.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(third.getStatus()).as("다른 주문은 계속 발행된다").isEqualTo(OutboxStatus.SENT);
    }

    @Test
    @DisplayName("한계를 넘긴 실패는 DEAD 가 되고 이후 발행 시도조차 되지 않는다")
    void exhaustedRetryBecomesDead() {
        OutboxEvent event = record("EVT-1");
        brokerDown();

        for (int i = 0; i < MAX_RETRY; i++) {
            pollAfterBackOff();
        }
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.DEAD);

        // DEAD 는 lockPendingBatch 대상이 아니므로 브로커가 살아나도 집히지 않는다
        brokerHealthy();
        pollAfterBackOff();

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.DEAD);
    }

    @Test
    @DisplayName("[D-003] 실패한 이벤트는 다음 시도 시각이 뒤로 밀린다")
    void failureSchedulesLaterAttempt() {
        OutboxEvent event = record("EVT-1");
        brokerDown();

        relay.relay();
        Instant first = event.getNextAttemptAt();

        pollAfterBackOff();
        Instant second = event.getNextAttemptAt();

        assertThat(first).isNotNull().isAfter(Instant.now());
        assertThat(second).isAfter(first);
    }

    @Test
    @DisplayName("[D-003] DEAD 가 된 이벤트도 회수하면 다시 발행된다")
    void deadEventCanBeRequeuedAndSent() {
        OutboxEvent paymentCompleted = record("EVT-1");
        brokerDown();
        for (int pollCycle = 0; pollCycle < MAX_RETRY; pollCycle++) {
            pollAfterBackOff();
        }
        assertThat(paymentCompleted.getStatus()).isEqualTo(OutboxStatus.DEAD);

        // 원인을 제거한 뒤 운영이 되살린다. 회수 경로가 없으면
        // 유실을 막으려고 만든 장치가 유실의 원인이 된다.
        paymentCompleted.requeue();
        brokerHealthy();
        relay.relay();

        assertThat(paymentCompleted.getStatus()).isEqualTo(OutboxStatus.SENT);
    }

    @Test
    @DisplayName("[D-013] 앞의 이벤트가 못 나갔으면 같은 애그리거트의 뒤 이벤트도 보류된다")
    void failureHoldsLaterEventsOfSameAggregate() {
        // 둘 다 partitionKey 가 ORD-1 이다. README 는 "같은 애그리거트의 순서가 보장된다"고 말한다.
        OutboxEvent earlier = record("EVT-1");
        OutboxEvent later = record("EVT-2");

        when(kafkaTemplate.send(any(ProducerRecord.class))).thenAnswer(invocation -> {
            ProducerRecord<String, String> sent = invocation.getArgument(0);
            return "EVT-1".equals(header(sent, EventHeaders.EVENT_ID))
                    ? CompletableFuture.failedFuture(new IllegalStateException("broker rejected"))
                    : CompletableFuture.completedFuture(mock(SendResult.class));
        });

        relay.relay();

        assertThat(earlier.getStatus()).isEqualTo(OutboxStatus.PENDING);

        // 수정 전에는 그냥 나갔다. PaymentCompleted 가 재시도되는 동안 PaymentCancelled 가 먼저 도착해
        // 회수할 라이선스가 없는 상태에서 회수가 실행되는 식의 역전이 가능했다.
        assertThat(later.getStatus())
                .as("같은 애그리거트의 뒤 이벤트")
                .isEqualTo(OutboxStatus.PENDING);
    }

    @Test
    @DisplayName("[D-014] 앞 이벤트가 백오프 대기 중이면 같은 키의 뒤 이벤트는 다음 회차에도 보류된다")
    void backOffDoesNotLetLaterEventOvertakeAcrossCycles() {
        OutboxEvent paymentCompleted = record("EVT-1");
        OutboxEvent paymentCancelled = record("EVT-2");

        // 1회차 — 앞 이벤트가 발행에 실패한다. D-013 수정 덕분에 뒤 이벤트는 이번 회차에서 보류된다.
        brokerDown();
        relay.relay();
        assertThat(paymentCompleted.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(paymentCancelled.getStatus()).isEqualTo(OutboxStatus.PENDING);

        // 2회차 — 브로커는 살아났지만 앞 이벤트는 아직 백오프 대기 중이다(시계를 밀지 않는다).
        //
        // 앞 이벤트: next_attempt_at = 실패시각 + 1초  → 조회에서 빠진다
        // 뒤 이벤트: next_attempt_at = NULL            → 조회에 잡힌다
        //
        // 두 건이 같은 배치에 오지 않으므로 웨이브 구조가 볼 수 있는 범위 밖이다.
        // 재시도 간격은 1초 → 2초 → 4초 …(상한 5분)로 벌어지는데 뒤 이벤트는 계속 즉시 대상이라,
        // 회차가 갈수록 창이 넓어진다. 브로커 복구 직후가 정확히 이 상태다.
        brokerHealthy();
        relay.relay();

        // 기대: 앞의 것이 아직 안 나갔으므로 뒤의 것도 기다린다.
        // 실제: 뒤의 것만 혼자 나간다. license 는 발급되지 않은 라이선스를 회수하려다 조용히 no-op 하고,
        //       뒤늦게 도착한 PaymentCompleted 로 라이선스를 발급한다 — 환불했는데 게임이 남는다.
        assertThat(paymentCancelled.getStatus())
                .as("앞 이벤트가 백오프 대기 중인 동안의 뒤 이벤트")
                .isEqualTo(OutboxStatus.PENDING);
    }

    @Test
    @DisplayName("[D-014] 순서 때문에 보류된 이벤트는 재시도 예산을 쓰지 않는다")
    void heldEventDoesNotBurnRetryBudget() {
        OutboxEvent earlier = record("EVT-1");
        OutboxEvent later = record("EVT-2");
        brokerDown();

        // 앞 이벤트가 MAX_RETRY 만큼 실패하는 동안 뒤 이벤트는 한 번도 시도되지 않았다.
        for (int pollCycle = 0; pollCycle < MAX_RETRY; pollCycle++) {
            pollAfterBackOff();
        }

        assertThat(earlier.getStatus()).isEqualTo(OutboxStatus.DEAD);
        assertThat(later.getRetryCount())
                .as("보류는 실패가 아니다 — 시도해 보지도 않고 DEAD 가 되면 안 된다")
                .isZero();
        assertThat(later.getStatus()).isEqualTo(OutboxStatus.PENDING);
    }

    @Test
    @DisplayName("[D-014] 앞 이벤트가 DEAD 가 되면 막힌 키가 풀린다 — 영구 정지를 막는 탈출구")
    void deadBlockerReleasesTheKey() {
        OutboxEvent blocker = record("EVT-1");
        OutboxEvent held = record("EVT-2");

        brokerDown();
        for (int pollCycle = 0; pollCycle < MAX_RETRY; pollCycle++) {
            pollAfterBackOff();
        }
        assertThat(blocker.getStatus()).isEqualTo(OutboxStatus.DEAD);

        brokerHealthy();
        pollAfterBackOff();

        // 순서 보장을 포기하는 유일한 지점이다. 대안은 그 키의 영구 정지인데,
        // 영원히 나가지 않을 이벤트 뒤에 키 전체를 묶어두는 쪽이 더 나쁘다.
        // 그래서 DEAD 알람이 이 설계의 짝이다(docs/event-ordering.md 6절 A-2).
        assertThat(held.getStatus())
                .as("DEAD 뒤에 묶여 있던 이벤트")
                .isEqualTo(OutboxStatus.SENT);
    }

    @Test
    @DisplayName("[D-013] 키가 다르면 앞의 실패에 영향받지 않는다 — 막히는 범위는 그 애그리거트뿐")
    void failureOfOneKeyDoesNotBlockOtherKeys() {
        OutboxEvent blocked = recordWithKey("EVT-1", "ORD-1");
        OutboxEvent other = recordWithKey("EVT-2", "ORD-2");

        when(kafkaTemplate.send(any(ProducerRecord.class))).thenAnswer(invocation -> {
            ProducerRecord<String, String> sent = invocation.getArgument(0);
            return "ORD-1".equals(sent.key())
                    ? CompletableFuture.failedFuture(new IllegalStateException("broker rejected"))
                    : CompletableFuture.completedFuture(mock(SendResult.class));
        });

        relay.relay();

        assertThat(blocked.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(other.getStatus()).as("다른 주문은 계속 흐른다").isEqualTo(OutboxStatus.SENT);
    }

    @Test
    @DisplayName("[D-013] 같은 키의 이벤트는 적재 순서대로 발행된다")
    void sameKeyIsPublishedInOrder() {
        recordWithKey("EVT-1", "ORD-1");
        recordWithKey("EVT-2", "ORD-1");
        recordWithKey("EVT-3", "ORD-1");
        brokerHealthy();

        relay.relay();

        ArgumentCaptor<ProducerRecord<String, String>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate, times(3)).send(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(sent -> header(sent, EventHeaders.EVENT_ID))
                .containsExactly("EVT-1", "EVT-2", "EVT-3");
    }

    @Test
    @DisplayName("배치가 가득 차면 다음 폴링을 기다리지 않고 이어서 비운다")
    void drainsContinuouslyWhileBatchesAreFull() {
        // 배치 크기의 2.5배를 쌓아두고 한 번만 호출한다.
        // 고정 주기를 기다렸다면 100건에서 멈췄을 것이다.
        for (int i = 0; i < BATCH_SIZE * 2 + 50; i++) {
            recordWithKey("EVT-" + i, "ORD-" + i);
        }
        brokerHealthy();

        relay.relay();

        assertThat(store).allMatch(event -> event.getStatus() == OutboxStatus.SENT);
    }

    @Test
    @DisplayName("한 회차가 스케줄러를 독점하지 않도록 배치 수에 상한이 있다")
    void drainIsBoundedPerCycle() {
        for (int i = 0; i < BATCH_SIZE * (MAX_BATCHES_PER_CYCLE + 3); i++) {
            recordWithKey("EVT-" + i, "ORD-" + i);
        }
        brokerHealthy();

        relay.relay();

        long sent = store.stream().filter(e -> e.getStatus() == OutboxStatus.SENT).count();
        assertThat(sent).isEqualTo((long) BATCH_SIZE * MAX_BATCHES_PER_CYCLE);
        assertThat(store).anyMatch(e -> e.getStatus() == OutboxStatus.PENDING);
    }

    @Test
    @DisplayName("릴레이가 발행 성공을 지표로 보고한다 — 적체를 사고 전에 보려면 필요하다")
    void reportsPublishedToMetrics() {
        recordWithKey("EVT-1", "ORD-1");
        recordWithKey("EVT-2", "ORD-2");
        brokerHealthy();

        relay.relay();

        assertThat(counter("stove.outbox.published")).isEqualTo(2.0);
        assertThat(meterRegistry.get("stove.outbox.relay").timer().count())
                .as("회차 소요시간도 남아야 p95 를 볼 수 있다")
                .isEqualTo(1);
        assertThat(meterRegistry.get("stove.outbox.batch.size").summary().totalAmount())
                .isEqualTo(2.0);
    }

    @Test
    @DisplayName("릴레이가 발행 실패도 지표로 보고한다")
    void reportsFailureToMetrics() {
        record("EVT-1");
        brokerDown();

        relay.relay();

        assertThat(counter("stove.outbox.failed")).isEqualTo(1.0);
        assertThat(counter("stove.outbox.dead")).as("아직 재시도가 남아 있다").isZero();
    }

    @Test
    @DisplayName("DEAD 전이는 별도 지표로 잡힌다 — 알람 대상이다")
    void reportsDeadToMetrics() {
        record("EVT-1");
        brokerDown();

        for (int pollCycle = 0; pollCycle < MAX_RETRY; pollCycle++) {
            pollAfterBackOff();
        }

        assertThat(counter("stove.outbox.dead")).isEqualTo(1.0);
    }

    private static String header(ProducerRecord<String, String> record, String name) {
        var header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
