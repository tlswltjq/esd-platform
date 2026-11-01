package com.stove.common.kafka.ops;

import com.stove.common.kafka.DeadLetterTopics;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.support.SendResult;

/**
 * DLT 를 들여다보고 원본 토픽으로 되돌린다. 조회는 커밋하지 않으므로
 * 조회가 보여주는 것이 곧 <b>재투입이 다음에 처리할 것</b>이다.
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
     * 중복 수신이 되지만 Inbox 멱등 가드가 막는다.
     *
     * @return 되돌린 건수
     */
    public int replay(String topic, int max) {
        try (Consumer<String, String> consumer = openConsumer(topic)) {
            List<ConsumerRecord<String, String>> records = drain(consumer, max);
            if (!records.isEmpty()) {
                awaitPublished(records);
                Map<TopicPartition, OffsetAndMetadata> offsets = new HashMap<>();
                for (ConsumerRecord<String, String> record : records) {
                    offsets.put(new TopicPartition(record.topic(), record.partition()),
                            new OffsetAndMetadata(record.offset() + 1));
                }
                // poll 위치는 max 밖의 레코드까지 전진할 수 있다. 실제 발행한 범위만 커밋한다.
                consumer.commitSync(offsets);
                log.warn("운영 재투입 — DLT {}건을 원본 토픽으로 되돌렸다 topic={} offsets={}",
                        records.size(), topic, offsets);
            }
            return records.size();
        }
    }

    private Consumer<String, String> openConsumer(String topic) {
        if (topic == null || !topic.endsWith(DeadLetterTopics.SUFFIX)
                || topic.length() == DeadLetterTopics.SUFFIX.length()) {
            throw new IllegalArgumentException("DLT 토픽 이름이 필요합니다: " + topic);
        }
        Properties overrides = new Properties();
        overrides.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        overrides.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        Consumer<String, String> consumer = consumerFactory.createConsumer(replayGroupId, "-ops", null, overrides);
        try {
            List<PartitionInfo> partitions = consumer.partitionsFor(topic);
            if (partitions == null || partitions.isEmpty()) {
                throw new IllegalArgumentException("그런 토픽이 없다: " + topic);
            }
            // subscribe 가 아니라 assign 이다 — 리밸런스를 기다리지 않고 바로 읽는다.
            consumer.assign(partitions.stream()
                    .map(partition -> new TopicPartition(partition.topic(), partition.partition()))
                    .toList());
            return consumer;
        } catch (RuntimeException failure) {
            consumer.close();
            throw failure;
        }
    }

    /** 더 나올 것이 없거나 {@code max} 를 채울 때까지 읽는다. */
    private List<ConsumerRecord<String, String>> drain(Consumer<String, String> consumer, int max) {
        if (max < 1) {
            throw new IllegalArgumentException("max 는 1 이상이어야 한다");
        }
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
     * 파티션은 지정하지 않는다(키가 그대로라 원래 파티션으로 간다).
     */
    private CompletableFuture<SendResult<String, String>> republish(ConsumerRecord<String, String> record) {
        String originalTopic = originalTopicOf(record);
        ProducerRecord<String, String> restored =
                new ProducerRecord<>(originalTopic, record.key(), record.value());
        for (Header header : record.headers()) {
            if (!header.key().startsWith(DLT_HEADER_PREFIX)) {
                restored.headers().add(header);
            }
        }
        return kafkaTemplate.send(restored);
    }

    private void awaitPublished(List<ConsumerRecord<String, String>> records) {
        try {
            CompletableFuture.allOf(records.stream().map(this::republish)
                    .toArray(CompletableFuture<?>[]::new)).get(30, TimeUnit.SECONDS);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new KafkaException("DLT 재투입이 중단되었습니다. 오프셋을 커밋하지 않습니다.", failure);
        } catch (ExecutionException | TimeoutException failure) {
            throw new KafkaException("DLT 재투입 발행을 확인하지 못했습니다. 오프셋을 커밋하지 않습니다.", failure);
        }
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
