package com.stove.common.kafka.ops;

import com.stove.common.kafka.DeadLetterTopics;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;

/**
 * DLT 를 들여다보고 원본 토픽으로 되돌린다.
 *
 * <p><b>왜 {@code @KafkaListener} 가 아니라 매번 컨슈머를 만드는가</b> —
 * 재투입은 상시 도는 작업이 아니라 <b>사람이 한 번 누르는 작업</b>이다. 상주 리스너로 두면
 * "언제 멈추는가"(유휴 감지)와 "왜 지금 돌고 있는가"를 계속 관리해야 하고,
 * 원인을 고치기 전에 자동으로 재투입되는 사고가 가능해진다.
 * 요청 하나에 컨슈머 하나를 열고 닫으면 <b>몇 건을 되돌렸는지 응답으로 돌려줄 수 있다.</b>
 *
 * <p>조회({@link #peek})와 재투입({@link #replay})은 같은 컨슈머 그룹을 쓰되
 * 조회는 커밋하지 않는다. 그래서 조회가 보여주는 것은 곧 <b>재투입이 다음에 처리할 것</b>이다.
 */
@Slf4j
@RequiredArgsConstructor
public class DltOpsService {

    /** 폴링 한 번의 대기. 남은 게 없으면 이 시간만 기다리고 끝낸다. */
    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(2);

    /** {@code DeadLetterPublishingRecoverer} 가 붙이는 진단 헤더들의 접두사. */
    private static final String DLT_HEADER_PREFIX = "kafka_dlt-";

    private final ConsumerFactory<String, String> consumerFactory;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final String replayGroupId;

    /** 커밋하지 않는다 — 보기만 하는 것이 상태를 바꾸면 안 된다. */
    public List<DltRecordResponse> peek(String topic, int max) {
        try (Consumer<String, String> consumer = openConsumer(topic)) {
            return drain(consumer, max).stream()
                    .map(DltRecordResponse::from)
                    .toList();
        }
    }

    /**
     * DLT 의 레코드를 원본 토픽으로 되돌린다.
     *
     * <p><b>원인을 먼저 고쳐야 한다.</b> 고치지 않고 재투입하면 같은 실패를 반복해 DLT 로 돌아온다.
     * 그 판단은 도구가 대신할 수 없으므로 {@link #peek} 로 원인을 보고 사람이 정한다.
     *
     * <p>재투입은 <b>중복 수신</b>이다. 그래도 안전한 이유는 Inbox 멱등 가드가
     * {@code (event_id, consumer_group)} 로 막기 때문이다 — 이미 처리에 성공한 이벤트라면
     * 재투입돼도 두 번 반영되지 않는다.
     *
     * @return 되돌린 건수
     */
    public int replay(String topic, int max) {
        try (Consumer<String, String> consumer = openConsumer(topic)) {
            List<ConsumerRecord<String, String>> records = drain(consumer, max);
            records.forEach(this::republish);
            if (!records.isEmpty()) {
                // 발행이 끝난 뒤에만 커밋한다. 순서가 뒤집히면 재투입에 실패한 레코드가 사라진다.
                kafkaTemplate.flush();
                consumer.commitSync();
                log.warn("운영 재투입 — DLT {}건을 원본 토픽으로 되돌렸다 topic={}", records.size(), topic);
            }
            return records.size();
        }
    }

    private Consumer<String, String> openConsumer(String topic) {
        Consumer<String, String> consumer = consumerFactory.createConsumer(replayGroupId, "-ops");
        List<PartitionInfo> partitions = consumer.partitionsFor(topic);
        if (partitions == null || partitions.isEmpty()) {
            consumer.close();
            throw new IllegalArgumentException("그런 토픽이 없다: " + topic);
        }
        // subscribe 가 아니라 assign 이다 — 리밸런스를 기다리지 않고 바로 읽는다.
        // 커밋된 오프셋이 있으면 거기서, 없으면 auto-offset-reset(earliest)에서 시작한다.
        consumer.assign(partitions.stream()
                .map(partition -> new TopicPartition(partition.topic(), partition.partition()))
                .toList());
        return consumer;
    }

    /** 더 나올 것이 없거나 {@code max} 를 채울 때까지 읽는다. */
    private List<ConsumerRecord<String, String>> drain(Consumer<String, String> consumer, int max) {
        List<ConsumerRecord<String, String>> collected = new ArrayList<>();
        while (collected.size() < max) {
            ConsumerRecords<String, String> polled = consumer.poll(POLL_TIMEOUT);
            if (polled.isEmpty()) {
                break;
            }
            for (ConsumerRecord<String, String> record : polled) {
                collected.add(record);
                if (collected.size() >= max) {
                    break;
                }
            }
        }
        return collected;
    }

    /**
     * 원본 토픽으로 되돌린다.
     *
     * <p>{@code kafka_dlt-*} 헤더는 떼고 보낸다. 붙여서 보내면 같은 레코드가 다시 실패했을 때
     * 이력이 겹쳐 쌓여 <b>어느 것이 이번 실패인지 알 수 없게 된다.</b>
     * 계약 헤더와 {@code traceparent} 는 그대로 따라가므로 멱등 판정과 추적은 유지된다.
     *
     * <p>파티션은 지정하지 않는다. 키가 그대로이므로 원래 있던 파티션으로 다시 간다 —
     * 같은 애그리거트의 순서 보장이 재투입에서도 유지된다.
     */
    private void republish(ConsumerRecord<String, String> record) {
        String originalTopic = originalTopicOf(record);
        ProducerRecord<String, String> restored =
                new ProducerRecord<>(originalTopic, record.key(), record.value());
        for (Header header : record.headers()) {
            if (!header.key().startsWith(DLT_HEADER_PREFIX)) {
                restored.headers().add(header);
            }
        }
        kafkaTemplate.send(restored);
    }

    /** 헤더가 정답이다. 없는 레코드(손으로 넣은 것 등)는 이름 규칙으로 되돌린다. */
    private String originalTopicOf(ConsumerRecord<String, String> record) {
        Header header = record.headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_TOPIC);
        if (header != null) {
            return new String(header.value(), StandardCharsets.UTF_8);
        }
        return DeadLetterTopics.originalOf(record.topic());
    }
}
