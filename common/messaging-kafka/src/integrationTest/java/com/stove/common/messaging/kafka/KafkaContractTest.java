package com.stove.common.messaging.kafka;

import com.stove.common.event.EventHandler;
import com.stove.common.event.KeyQuarantine;
import com.stove.common.event.OutboundMessage;
import com.stove.common.messaging.contract.MessagingContractTest;
import com.stove.common.testcontainers.InfraContainers;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.MessageListener;
import org.testcontainers.kafka.ConfluentKafkaContainer;

/**
 * Kafka adapter 가 공통 계약(결정 202·206)을 지키는지 실제 Kafka·MySQL 로 본다(결정 209).
 *
 * <p>운영에서는 Spring Kafka 등록기({@link KafkaEventSubscriptions#configureKafkaListeners})가 컨테이너를 만든다.
 * 여기서는 그룹·토픽을 테스트마다 바꿔야 해서 컨테이너를 직접 만들되, 수신({@link KafkaEventSubscriptions.Receiver}),
 * 격리 연결({@link KafkaEventSubscriptions#toQuarantine}), 오류 처리기({@link DeadLetterErrorHandlers},
 * 재시도 {@link ConsumerRetryPolicy} 1·2·4초)는 운영과 같은 부품을 쓴다.
 */
@SpringBootTest(properties = "stove.outbox.relay-enabled=false")
@Import({InfraContainers.MySql.class, InfraContainers.Kafka.class})
class KafkaContractTest extends MessagingContractTest {

    private static final int PARTITIONS = 3;

    @Autowired
    ConfluentKafkaContainer kafkaContainer;

    private DefaultKafkaProducerFactory<String, String> producers;
    private KafkaMessagePublisher publisher;
    private final Map<String, List<ConcurrentMessageListenerContainer<String, String>>> containersByGroup = new LinkedHashMap<>();

    @BeforeEach
    void connect() {
        Map<String, Object> producerConfig = new HashMap<>();
        producerConfig.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaContainer.getBootstrapServers());
        producerConfig.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        producerConfig.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        producerConfig.put(ProducerConfig.ACKS_CONFIG, "all");
        producers = new DefaultKafkaProducerFactory<>(producerConfig);
        publisher = new KafkaMessagePublisher(new KafkaTemplate<>(producers));
    }

    @Override
    protected void closeAdapter() {
        containersByGroup.clear();
        producers.destroy();
    }

    @Override
    protected Duration timeout() {
        // 재시도 1·2·4초에 격리 기록 실패 시 재시도 한 바퀴가 다시 돈다. 리밸런스도 몇 초 걸린다.
        return Duration.ofSeconds(90);
    }

    @Override
    protected void prepareTopic(String topic) {
        Map<String, Object> adminConfig = Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaContainer.getBootstrapServers());
        try (Admin admin = Admin.create(adminConfig)) {
            admin.createTopics(List.of(new NewTopic(topic, PARTITIONS, (short) 1))).all().get(30, TimeUnit.SECONDS);
        } catch (Exception failure) {
            throw new IllegalStateException("테스트 토픽을 만들지 못했다: " + topic, failure);
        }
    }

    @Override
    protected AutoCloseable subscribe(String group, String topic, KeyQuarantine storage, EventHandler handler,
                                      List<Class<? extends RuntimeException>> noRetryFor) {
        Map<String, Object> consumerConfig = new HashMap<>();
        consumerConfig.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaContainer.getBootstrapServers());
        consumerConfig.put(ConsumerConfig.GROUP_ID_CONFIG, group);
        consumerConfig.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumerConfig.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerConfig.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        DefaultKafkaConsumerFactory<String, String> consumers = new DefaultKafkaConsumerFactory<>(consumerConfig);

        KafkaEventSubscriptions.Receiver receiver = new KafkaEventSubscriptions.Receiver(handler, group, storage);
        ContainerProperties properties = new ContainerProperties(topic);
        properties.setMessageListener((MessageListener<String, String>) receiver::onMessage);
        ConcurrentMessageListenerContainer<String, String> container = new ConcurrentMessageListenerContainer<>(consumers, properties);
        container.setCommonErrorHandler(DeadLetterErrorHandlers.create(
                KafkaEventSubscriptions.toQuarantine(group, storage), asArray(noRetryFor)));
        container.start();
        containersByGroup.computeIfAbsent(group, ignored -> new ArrayList<>()).add(container);
        return container::stop;
    }

    /** 그룹마다 인스턴스들이 파티션을 모두 나눠 가질 때까지(리밸런스 끝) 기다린다. 도중에 보내면 재배달이 섞인다. */
    @Override
    protected void awaitSubscriptionsReady() {
        for (Map.Entry<String, List<ConcurrentMessageListenerContainer<String, String>>> entry : containersByGroup.entrySet()) {
            List<ConcurrentMessageListenerContainer<String, String>> containers = entry.getValue();
            await("그룹 " + entry.getKey() + " 파티션 배정", () -> assigned(containers) == PARTITIONS
                    && containers.stream().allMatch(container -> partitionsOf(container) > 0));
        }
    }

    private static int partitionsOf(ConcurrentMessageListenerContainer<String, String> container) {
        return container.getAssignedPartitions() == null ? 0 : container.getAssignedPartitions().size();
    }

    private static int assigned(List<ConcurrentMessageListenerContainer<String, String>> containers) {
        return containers.stream().mapToInt(KafkaContractTest::partitionsOf).sum();
    }

    @Override
    protected void publish(OutboundMessage message) {
        publisher.publish(message).orTimeout(10, TimeUnit.SECONDS).join();
    }
}
