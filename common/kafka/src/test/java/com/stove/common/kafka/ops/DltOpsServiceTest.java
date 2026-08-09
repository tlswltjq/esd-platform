package com.stove.common.kafka.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.stove.common.event.EventType;
import com.stove.common.event.Topics;
import com.stove.common.event.kafka.EventHeaders;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.support.SendResult;

/**
 * DLT 조회와 재투입.
 *
 * <p>브로커를 띄우지 않는다. 검증 대상은 카프카 구현이 아니라 <b>무엇을 어디로 되돌리고,
 * 그때 무엇을 떼고 무엇을 남기는가</b>이므로 {@link MockConsumer} 로 레코드를 넣고
 * 발행 측을 대역으로 잡는다.
 */
class DltOpsServiceTest {

    private static final String DLT_TOPIC = Topics.PAYMENT + ".DLT";
    private static final TopicPartition PARTITION = new TopicPartition(DLT_TOPIC, 0);
    private static final String TRACE_PARENT = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

    /**
     * 커밋 횟수를 센다. 서비스가 컨슈머를 닫고 나가므로 <b>끝난 뒤에는 물어볼 수 없어서</b>
     * 호출 자체를 붙잡는다.
     */
    private final AtomicInteger commits = new AtomicInteger();

    private final MockConsumer<String, String> consumer =
            new MockConsumer<>(OffsetResetStrategy.EARLIEST) {
                @Override
                public synchronized void commitSync() {
                    commits.incrementAndGet();
                    super.commitSync();
                }
            };

    @SuppressWarnings("unchecked")
    private final ConsumerFactory<String, String> consumerFactory = mock(ConsumerFactory.class);
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);

    private final DltOpsService service = new DltOpsService(consumerFactory, kafkaTemplate, "payment-dlt-ops");

    private long nextOffset = 0;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        consumer.updatePartitions(DLT_TOPIC,
                List.of(new PartitionInfo(DLT_TOPIC, 0, null, new org.apache.kafka.common.Node[0],
                        new org.apache.kafka.common.Node[0])));
        consumer.updateBeginningOffsets(Map.of(PARTITION, 0L));
        when(consumerFactory.createConsumer(anyString(), anyString())).thenReturn(consumer);
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
    }

    /**
     * {@code DeadLetterPublishingRecoverer} 가 만들어 놓는 모습 그대로의 DLT 레코드.
     *
     * <p><b>폴링 시점에 넣는다.</b> {@link MockConsumer} 는 아직 배정되지 않은 파티션에 레코드를
     * 받지 않는데, 배정은 서비스가 컨슈머를 열면서 하기 때문이다 —
     * 테스트가 미리 넣으려 하면 "not assigned" 로 거절당한다.
     */
    private ConsumerRecord<String, String> deadLetter(String eventId) {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                DLT_TOPIC, 0, nextOffset++, "ORD-1", "{\"orderNo\":\"ORD-1\"}");
        record.headers().add(EventHeaders.EVENT_ID, eventId.getBytes(StandardCharsets.UTF_8));
        record.headers().add(EventHeaders.EVENT_TYPE,
                EventType.PAYMENT_COMPLETED.getBytes(StandardCharsets.UTF_8));
        record.headers().add(EventHeaders.TRACE_PARENT, TRACE_PARENT.getBytes(StandardCharsets.UTF_8));
        record.headers().add(KafkaHeaders.DLT_ORIGINAL_TOPIC, Topics.PAYMENT.getBytes(StandardCharsets.UTF_8));
        record.headers().add(KafkaHeaders.DLT_EXCEPTION_MESSAGE,
                "connection pool exhausted".getBytes(StandardCharsets.UTF_8));
        consumer.schedulePollTask(() -> consumer.addRecord(record));
        return record;
    }

    private static String header(ProducerRecord<String, String> record, String name) {
        var header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("조회는 재투입 판단에 필요한 것을 보여준다 — 원본 토픽과 실패 원인")
    void peekShowsCause() {
        deadLetter("EVT-1");

        List<DltRecordResponse> peeked = service.peek(DLT_TOPIC, 10);

        assertThat(peeked).singleElement().satisfies(response -> {
            assertThat(response.eventId()).isEqualTo("EVT-1");
            assertThat(response.originalTopic()).isEqualTo(Topics.PAYMENT);
            assertThat(response.exception()).contains("connection pool exhausted");
            assertThat(response.traceParent()).isEqualTo(TRACE_PARENT);
        });
    }

    /** 보기만 하는 것이 상태를 바꾸면, 두 번 열어본 사람이 다른 것을 보게 된다. */
    @Test
    @DisplayName("조회는 커밋하지 않는다 — 몇 번을 봐도 재투입 대상이 그대로다")
    void peekDoesNotCommit() {
        deadLetter("EVT-1");

        service.peek(DLT_TOPIC, 10);

        assertThat(commits).hasValue(0);
        verify(kafkaTemplate, never()).send(any(ProducerRecord.class));
    }

    /** 커밋이 발행보다 먼저면, 재투입에 실패한 레코드가 DLT 에서도 사라진다. */
    @Test
    @DisplayName("재투입은 발행이 끝난 뒤에만 커밋한다")
    void replayCommitsAfterPublishing() {
        deadLetter("EVT-1");

        service.replay(DLT_TOPIC, 10);

        verify(kafkaTemplate).flush();
        assertThat(commits).hasValue(1);
    }

    @Test
    @DisplayName("재투입은 원본 토픽으로 보내고, 키를 유지해 파티션이 바뀌지 않게 한다")
    void replaySendsBackToOriginalTopic() {
        deadLetter("EVT-1");

        assertThat(service.replay(DLT_TOPIC, 10)).isEqualTo(1);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<ProducerRecord<String, String>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());
        ProducerRecord<String, String> sent = captor.getValue();

        assertThat(sent.topic()).isEqualTo(Topics.PAYMENT);
        assertThat(sent.key()).isEqualTo("ORD-1");
        assertThat(sent.partition()).as("파티션은 키가 정한다 — 지정하면 순서 보장이 깨진다").isNull();
    }

    /**
     * 계약 헤더가 빠지면 컨슈머 입구({@code EventEnvelope.from})가 거부하고,
     * traceparent 가 빠지면 재투입분이 원래 요청과 이어지지 않는다.
     */
    @Test
    @DisplayName("계약 헤더와 추적 컨텍스트는 그대로 따라간다")
    void replayKeepsContractHeaders() {
        deadLetter("EVT-1");

        service.replay(DLT_TOPIC, 10);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<ProducerRecord<String, String>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());

        assertThat(header(captor.getValue(), EventHeaders.EVENT_ID)).isEqualTo("EVT-1");
        assertThat(header(captor.getValue(), EventHeaders.EVENT_TYPE)).isEqualTo(EventType.PAYMENT_COMPLETED);
        assertThat(header(captor.getValue(), EventHeaders.TRACE_PARENT)).isEqualTo(TRACE_PARENT);
    }

    /** 붙여서 보내면 다시 실패했을 때 이력이 겹쳐 쌓여 어느 것이 이번 실패인지 알 수 없다. */
    @Test
    @DisplayName("진단 헤더(kafka_dlt-*)는 떼고 보낸다")
    void replayStripsDeadLetterHeaders() {
        deadLetter("EVT-1");

        service.replay(DLT_TOPIC, 10);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<ProducerRecord<String, String>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());

        assertThat(captor.getValue().headers())
                .extracting(org.apache.kafka.common.header.Header::key)
                .noneMatch(key -> key.startsWith("kafka_dlt-"));
    }

    @Test
    @DisplayName("max 를 넘겨 가져오지 않는다 — 장애 중에 힙을 같이 무너뜨리지 않기 위해")
    void replayHonoursMax() {
        deadLetter("EVT-1");
        deadLetter("EVT-2");
        deadLetter("EVT-3");

        assertThat(service.replay(DLT_TOPIC, 2)).isEqualTo(2);
        verify(kafkaTemplate, org.mockito.Mockito.times(2)).send(any(ProducerRecord.class));
    }

    @Test
    @DisplayName("비어 있으면 0 건이고 아무것도 발행하지 않는다")
    void replayOnEmptyTopic() {
        assertThat(service.replay(DLT_TOPIC, 10)).isZero();

        verify(kafkaTemplate, never()).send(any(ProducerRecord.class));
    }

    @Test
    @DisplayName("없는 토픽은 400 으로 거절한다 — 오타를 조용히 0건으로 돌려주지 않는다")
    void unknownTopicIsRejected() {
        assertThatThrownBy(() -> service.peek("stove.없는토픽.v1.DLT", 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("없는토픽");
    }
}
