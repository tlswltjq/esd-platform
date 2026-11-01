package com.stove.common.kafka.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

import com.stove.common.kafka.EventHeaders;
import com.stove.common.testcontainers.SharedContainers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;

class DltReplayIntegrationTest {
    @BeforeAll
    static void broker() {
        SharedContainers.KAFKA.start();
    }

    @Test
    void boundedReplayAndRecreatedOperatorsDoNotSkipRecordsAcrossPartitions() throws Exception {
        try (Fixture f = new Fixture()) {
            for (int i = 0; i < 6; i++) {
                f.deadLetter(i, i % 2);
            }
            assertThat(f.service(f.kafka).peek(f.dlt, 2)).hasSize(2);
            assertThat(f.service(f.kafka).peek(f.dlt, 2)).hasSize(2);
            assertThat(f.committed()).isEmpty();

            int replayed = 0;
            for (int attempts = 0; replayed < 6 && attempts < 8; attempts++) {
                int count = f.service(f.kafka).replay(f.dlt, 2);
                assertThat(count).isBetween(0, 2);
                replayed += count;
                assertThat(f.committed().values().stream().mapToLong(OffsetAndMetadata::offset).sum())
                        .isEqualTo(replayed);
            }
            assertThat(replayed).isEqualTo(6);
            assertThat(f.service(f.kafka).replay(f.dlt, 2)).isZero();
            List<ConsumerRecord<String, String>> restored = f.originalRecords(6);
            assertThat(restored).hasSize(6);
            assertThat(restored.stream().map(record -> record.key()).toList())
                    .containsExactlyInAnyOrder("order-0", "order-1", "order-2", "order-3", "order-4", "order-5");
            for (ConsumerRecord<String, String> record : restored) {
                assertThat(record.headers().lastHeader(EventHeaders.EVENT_ID)).isNotNull();
                assertThat(record.headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_TOPIC)).isNull();
                assertThat(record.value()).isEqualTo("body-" + record.key());
            }
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void aPartialPublishFailureKeepsTheCursorAndNextOperatorCanReplayTheWholeBatch() throws Exception {
        try (Fixture f = new Fixture()) {
            f.deadLetter(0, 0);
            f.deadLetter(1, 0);
            KafkaTemplate<String, String> failing = spy(f.kafka);
            doAnswer(call -> {
                ProducerRecord<String, String> record = call.getArgument(0);
                if (record.key().equals("order-1")) {
                    return CompletableFuture.failedFuture(new IllegalStateException("fixture send failure"));
                }
                return call.callRealMethod();
            }).when(failing).send(any(ProducerRecord.class));

            assertThatThrownBy(() -> f.service(failing).replay(f.dlt, 2)).isInstanceOf(KafkaException.class);
            assertThat(f.committed()).isEmpty();
            assertThat(f.service(f.kafka).peek(f.dlt, 2)).hasSize(2);
            assertThat(f.service(f.kafka).replay(f.dlt, 2)).isEqualTo(2);
            assertThat(f.committed().get(new TopicPartition(f.dlt, 0)).offset()).isEqualTo(2L);
            List<ConsumerRecord<String, String>> restored = f.originalRecords(3);
            assertThat(restored).hasSize(3);
            Set<String> ids = new HashSet<>();
            for (ConsumerRecord<String, String> record : restored) {
                ids.add(new String(record.headers().lastHeader(EventHeaders.EVENT_ID).value(), StandardCharsets.UTF_8));
            }
            assertThat(ids).containsExactlyInAnyOrder("event-0", "event-1");
            assertThat(restored.stream().filter(record -> record.key().equals("order-0")).count()).isEqualTo(2);
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final String topic = "p3-" + UUID.randomUUID();
        private final String dlt = topic + ".DLT";
        private final String group = topic + "-ops";
        private final Admin admin;
        private final DefaultKafkaProducerFactory<String, String> producers;
        private final KafkaTemplate<String, String> kafka;
        private final DefaultKafkaConsumerFactory<String, String> consumers;

        private Fixture() throws Exception {
            String bootstrap = SharedContainers.KAFKA.getBootstrapServers();
            admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap));
            admin.createTopics(List.of(new NewTopic(topic, 2, (short) 1), new NewTopic(dlt, 2, (short) 1)))
                    .all().get(15, TimeUnit.SECONDS);
            producers = new DefaultKafkaProducerFactory<>(Map.of(
                    ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap,
                    ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                    ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class));
            kafka = new KafkaTemplate<>(producers);
            // 운영 소비자는 애플리케이션의 latest/자동 커밋 설정을 상속하면 안 된다.
            consumers = new DefaultKafkaConsumerFactory<>(Map.of(
                    ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap,
                    ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                    ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                    ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest",
                    ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, true));
        }

        private DltOpsService service(KafkaTemplate<String, String> template) {
            return new DltOpsService(consumers, template, group);
        }

        private void deadLetter(int number, int partition) throws Exception {
            ProducerRecord<String, String> record = new ProducerRecord<>(dlt, partition,
                    "order-" + number, "body-order-" + number);
            record.headers().add(EventHeaders.EVENT_ID, ("event-" + number).getBytes(StandardCharsets.UTF_8));
            record.headers().add(KafkaHeaders.DLT_ORIGINAL_TOPIC, topic.getBytes(StandardCharsets.UTF_8));
            kafka.send(record).get(15, TimeUnit.SECONDS);
        }

        private Map<TopicPartition, OffsetAndMetadata> committed() throws Exception {
            return admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get(15, TimeUnit.SECONDS);
        }

        private List<ConsumerRecord<String, String>> originalRecords(int expected) {
            Map<String, Object> overrides = new HashMap<>(consumers.getConfigurationProperties());
            overrides.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
            overrides.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
            List<ConsumerRecord<String, String>> records = new ArrayList<>();
            try (Consumer<String, String> reader = new DefaultKafkaConsumerFactory<String, String>(overrides)
                    .createConsumer(topic + "-observer")) {
                reader.assign(List.of(new TopicPartition(topic, 0), new TopicPartition(topic, 1)));
                long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
                while (records.size() < expected && System.nanoTime() < deadline) {
                    reader.poll(Duration.ofMillis(500)).forEach(records::add);
                }
            }
            return records;
        }

        @Override
        public void close() {
            kafka.destroy();
            producers.destroy();
            admin.close();
        }
    }
}
