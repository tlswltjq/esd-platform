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
 * DLT 를 들여다보고 원본 토픽으로 되돌린다. 조회는 커밋하지 않으므로
 * 조회가 보여주는 것이 곧 <b>재투입이 다음에 처리할 것</b>이다. docs/code-notes.md
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
     * DLT 의 레코드를 원본 토픽으로 되돌린다. <b>원인을 먼저 고쳐야 한다.</b>
     * 중복 수신이 되지만 Inbox 멱등 가드가 막는다. docs/code-notes.md
     *
     * @return 되돌린 건수
     */
    public int replay(String topic, int max) {
        try (Consumer<String, String> consumer = openConsumer(topic)) {
            List<ConsumerRecord<String, String>> records = drain(consumer, max);
            records.forEach(this::republish);
            if (!records.isEmpty()) {
                // 발행이 끝난 뒤에만 커밋한다 — 뒤집히면 실패한 레코드가 사라진다.
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
     * 원본 토픽으로 되돌린다. {@code kafka_dlt-*} 헤더는 <b>떼고</b> 보내고,
     * 파티션은 지정하지 않는다(키가 그대로라 원래 파티션으로 간다). docs/code-notes.md
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
