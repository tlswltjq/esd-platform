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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
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
    private OutboxRelay relay;

    @BeforeEach
    void setUp() {
        // 실제 lockPendingBatch 처럼 PENDING 만, id 순으로 집어준다.
        // next_attempt_at 시간 필터는 SQL 쪽 조건이라 여기서는 모사하지 않는다 —
        // 그쪽은 실제 MySQL 을 띄우는 OutboxBackOffQueryTest 가 검증한다.
        when(repository.lockPendingBatch(anyInt())).thenAnswer(invocation -> store.stream()
                .filter(event -> event.getStatus() == OutboxStatus.PENDING)
                .limit(invocation.<Integer>getArgument(0))
                .toList());

        relay = new OutboxRelay(repository, kafkaTemplate,
                new OutboxProperties(true, BATCH_SIZE, 1000L, MAX_RETRY, MAX_BATCHES_PER_CYCLE),
                new OutboxMetrics(new SimpleMeterRegistry(), repository),
                directTransactionTemplate());
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
        relay.relay();
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);

        brokerHealthy();
        relay.relay();

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
            relay.relay();
        }
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.DEAD);

        // DEAD 는 lockPendingBatch 대상이 아니므로 브로커가 살아나도 집히지 않는다
        brokerHealthy();
        relay.relay();

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.DEAD);
    }

    @Test
    @DisplayName("[D-003] 실패한 이벤트는 다음 시도 시각이 뒤로 밀린다")
    void failureSchedulesLaterAttempt() {
        OutboxEvent event = record("EVT-1");
        brokerDown();

        relay.relay();
        Instant first = event.getNextAttemptAt();

        relay.relay();
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
            relay.relay();
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

    private static String header(ProducerRecord<String, String> record, String name) {
        var header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
