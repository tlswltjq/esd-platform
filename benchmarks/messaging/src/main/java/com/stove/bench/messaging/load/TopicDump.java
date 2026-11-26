package com.stove.bench.messaging.load;

import com.stove.common.contract.EventHeaders;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;

/**
 * 토픽 하나(보통 {@code <토픽>.DLT})를 처음부터 끝 offset까지 읽어 메시지 ID를 CSV로 남긴다. 소비 그룹을 쓰지 않는다(assign) —
 * 시험 앱의 그룹 offset을 건드리지 않는다. 토픽이 없으면 빈 파일(머리 행만)을 쓴다.
 *
 * <p>{@code --bootstrap=... --topic=... --out=...}
 */
public final class TopicDump {

    static final String CSV_HEADER = "event_id,partition,offset,timestamp_ms";

    private TopicDump() {
    }

    public static void main(String[] args) throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        for (String arg : args) {
            if (!arg.startsWith("--") || !arg.contains("=")) {
                throw new IllegalArgumentException("인자는 --이름=값 형식이다: " + arg);
            }
            values.put(arg.substring(2, arg.indexOf('=')), arg.substring(arg.indexOf('=') + 1));
        }
        long count = dump(values.get("bootstrap"), values.get("topic"), Path.of(values.get("out")));
        System.out.println(count);
    }

    static long dump(String bootstrap, String topic, Path out) throws IOException {
        Properties config = new Properties();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        long count = 0;
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(config);
             BufferedWriter writer = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            writer.write(CSV_HEADER);
            writer.write('\n');
            List<PartitionInfo> partitions = consumer.listTopics(Duration.ofSeconds(30)).get(topic);
            if (partitions == null) {
                return 0;
            }
            List<TopicPartition> assigned = partitions.stream()
                    .map(info -> new TopicPartition(topic, info.partition())).toList();
            consumer.assign(assigned);
            consumer.seekToBeginning(assigned);
            Map<TopicPartition, Long> end = consumer.endOffsets(assigned);
            while (assigned.stream().anyMatch(partition -> consumer.position(partition) < end.get(partition))) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(1));
                for (ConsumerRecord<String, String> record : records) {
                    String eventId = EventHeaders.lastValue(record.headers(), EventHeaders.EVENT_ID);
                    writer.write(String.join(",", eventId == null ? "" : eventId, Integer.toString(record.partition()),
                            Long.toString(record.offset()), Long.toString(record.timestamp())));
                    writer.write('\n');
                    count++;
                }
            }
        }
        return count;
    }
}
