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
import java.nio.charset.StandardCharsets;
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

    private final OutboxEventRepository repository = mock(OutboxEventRepository.class);
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);

    private final List<OutboxEvent> store = new ArrayList<>();
    private OutboxRelay relay;

    @BeforeEach
    void setUp() {
        // 실제 lockPendingBatch 처럼 PENDING 만, id 순으로 집어준다
        when(repository.lockPendingBatch(anyInt())).thenAnswer(invocation -> store.stream()
                .filter(event -> event.getStatus() == OutboxStatus.PENDING)
                .limit(invocation.<Integer>getArgument(0))
                .toList());

        relay = new OutboxRelay(repository, kafkaTemplate,
                new OutboxProperties(true, 100, 1000L, MAX_RETRY));
    }

    private OutboxEvent record(String eventId) {
        OutboxEvent event = OutboxEvent.pending(eventId, "Payment", "ORD-1",
                EventType.PAYMENT_COMPLETED, Topics.PAYMENT, "ORD-1", "{\"orderNo\":\"ORD-1\"}");
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
    @DisplayName("배치 중 한 건이 실패해도 나머지는 계속 발행한다")
    void oneFailureDoesNotBlockBatch() {
        OutboxEvent first = record("EVT-1");
        OutboxEvent second = record("EVT-2");
        OutboxEvent third = record("EVT-3");

        // 2번만 실패하는 브로커
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenAnswer(invocation -> {
            ProducerRecord<String, String> sent = invocation.getArgument(0);
            String eventId = header(sent, EventHeaders.EVENT_ID);
            return "EVT-2".equals(eventId)
                    ? CompletableFuture.failedFuture(new IllegalStateException("record too large"))
                    : CompletableFuture.completedFuture(mock(SendResult.class));
        });

        relay.relay();

        assertThat(first.getStatus()).isEqualTo(OutboxStatus.SENT);
        assertThat(second.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(third.getStatus()).as("실패 뒤 이벤트도 발행되어야 한다").isEqualTo(OutboxStatus.SENT);
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
    @Tag("known-defect")
    @DisplayName("[D-003] 브로커가 오래 끊겼다 복구되면 밀린 이벤트가 결국 발행되어야 한다")
    void shouldSurviveProlongedOutage() {
        OutboxEvent paymentCompleted = record("EVT-1");
        brokerDown();

        // 장애가 (max-retry × poll-interval) 을 넘겨 지속되는 상황.
        // 기본 설정(max-retry 10, poll 1초)에서는 겨우 10초다.
        // 브로커 롤링 재시작이나 리더 선출은 그보다 오래 걸리는 일이 흔하다.
        for (int pollCycle = 0; pollCycle < MAX_RETRY * 3; pollCycle++) {
            relay.relay();
        }

        brokerHealthy();
        relay.relay();

        // 기대: 복구되면 결국 나간다(Outbox 의 존재 이유).
        // 실제: DEAD 로 굳어 영구 유실. 결제 완료 이벤트가 이렇게 되면
        //       돈은 받고 라이선스·주문확정·정산이 전부 일어나지 않는다.
        assertThat(paymentCompleted.getStatus())
                .as("복구 후 발행 여부")
                .isEqualTo(OutboxStatus.SENT);
    }

    private static String header(ProducerRecord<String, String> record, String name) {
        var header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
