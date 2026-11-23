package com.stove.common.messaging.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import com.stove.common.event.EventHandler;
import com.stove.common.event.EventSubscriber;
import com.stove.common.event.KeyQuarantine;
import com.stove.common.event.OutboundMessage;
import com.stove.common.messaging.contract.MessagingContractTest;
import com.stove.common.testcontainers.InfraContainers;
import java.lang.annotation.Annotation;
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
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.MessageListener;
import org.testcontainers.kafka.ConfluentKafkaContainer;

/**
 * Kafka adapter 가 공통 계약(결정 202·206)을 지키는지 실제 Kafka·MySQL 로 본다(결정 209).
 *
 * <p>컨테이너는 운영 팩토리({@link KafkaEventSubscriptions#factoryFor})로 만든다: Boot Kafka 설정, 격리 오류 처리기
 * ({@link DeadLetterErrorHandlers}, 재시도 {@link ConsumerRetryPolicy} 1·2·4초), {@code oneAtATime}. 수신도 운영의
 * {@link KafkaEventSubscriptions.Receiver} 다. 운영의 엔드포인트 등록 단계만 건너뛴다(그룹·토픽을 테스트마다 바꾼다).
 */
@SpringBootTest(properties = {"stove.outbox.relay-enabled=false", "spring.kafka.consumer.auto-offset-reset=earliest"})
@Import({InfraContainers.MySql.class, InfraContainers.Kafka.class})
class KafkaContractTest extends MessagingContractTest {

    private static final int PARTITIONS = 3;

    @Autowired
    ConfluentKafkaContainer kafkaContainer;

    @Autowired
    KafkaEventSubscriptions subscriptions;

    private DefaultKafkaProducerFactory<String, String> producers;
    private KafkaMessagePublisher publisher;
    private final Map<String, List<ConcurrentMessageListenerContainer<Object, Object>>> containersByGroup = new LinkedHashMap<>();

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
    protected Duration quietPeriod() {
        // 리밸런스 직후 커밋 전 레코드가 다시 오는 경우까지 지켜본다.
        return Duration.ofSeconds(4);
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
        ConcurrentMessageListenerContainer<Object, Object> container =
                container(declaration(group, topic, asArray(noRetryFor), false), storage, handler);
        container.start();
        containersByGroup.computeIfAbsent(group, ignored -> new ArrayList<>()).add(container);
        return container::stop;
    }

    /**
     * 운영과 같은 팩토리({@link KafkaEventSubscriptions#factoryFor}: Boot 설정·격리 오류 처리기·{@code oneAtATime})로
     * 컨테이너를 만든다. 운영의 엔드포인트 등록({@link KafkaEventSubscriptions#configureKafkaListeners})만 건너뛴다 —
     * 그룹·토픽을 테스트마다 바꿔야 해서다. 그 단계는 {@code KafkaEventSubscriptionsTest} 가 본다(PR #219 R2, 부분 반영).
     */
    @SuppressWarnings("unchecked")
    private ConcurrentMessageListenerContainer<Object, Object> container(EventSubscriber declaration, KeyQuarantine storage,
                                                                       EventHandler handler) {
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory = subscriptions.factoryFor(declaration, storage);
        ConcurrentMessageListenerContainer<Object, Object> container = factory.createContainer(declaration.topics());
        container.getContainerProperties().setGroupId(declaration.group());
        KafkaEventSubscriptions.Receiver receiver = new KafkaEventSubscriptions.Receiver(handler, declaration.group(), storage);
        // 리스너 컨테이너는 문자열 레코드만 받는다(Boot 의 StringDeserializer 설정). 팩토리 제네릭이 Object 라 좁힌다.
        container.getContainerProperties().setMessageListener((MessageListener<Object, Object>) record ->
                receiver.onMessage((ConsumerRecord<String, String>) (ConsumerRecord<?, ?>) record));
        return container;
    }

    /** 운영의 {@code @EventSubscriber} 선언과 같은 값을 테스트마다 다른 그룹·토픽으로 만든다. */
    private static EventSubscriber declaration(String group, String topic, Class<? extends RuntimeException>[] noRetryFor,
                                               boolean oneAtATime) {
        return new EventSubscriber() {
            @Override
            public Class<? extends Annotation> annotationType() {
                return EventSubscriber.class;
            }

            @Override
            public String[] topics() {
                return new String[]{topic};
            }

            @Override
            public String group() {
                return group;
            }

            @Override
            public Class<? extends RuntimeException>[] noRetryFor() {
                return noRetryFor;
            }

            @Override
            public boolean oneAtATime() {
                return oneAtATime;
            }
        };
    }

    /** Kafka 고유: {@code oneAtATime} 선언은 운영 팩토리에서 한 번에 한 건만 가져오게 한다(R2). */
    @Test
    void oneAtATimeDeclarationsPollOneRecordAtATime() {
        Class<? extends RuntimeException>[] none = asArray(List.of());
        ConcurrentMessageListenerContainer<Object, Object> single =
                container(declaration(group, topic, none, true), quarantine, new Recording());
        ConcurrentMessageListenerContainer<Object, Object> batch =
                container(declaration(group, topic, none, false), quarantine, new Recording());

        assertThat(single.getContainerProperties().getKafkaConsumerProperties()
                .getProperty(ConsumerConfig.MAX_POLL_RECORDS_CONFIG)).isEqualTo("1");
        assertThat(batch.getContainerProperties().getKafkaConsumerProperties()
                .getProperty(ConsumerConfig.MAX_POLL_RECORDS_CONFIG)).isNull();
        assertThat(single.getCommonErrorHandler()).as("격리 오류 처리기가 운영 팩토리에서 붙는다").isNotNull();
        assertThat(batch.getConcurrency()).as("결정 212: 리스너 동시성 기본값 = 파티션 수").isEqualTo(KafkaListenerDefaults.PARTITIONS);
    }

    /** 그룹마다 인스턴스들이 파티션을 모두 나눠 가질 때까지(리밸런스 끝) 기다린다. 도중에 보내면 재배달이 섞인다. */
    @Override
    protected void awaitSubscriptionsReady() {
        for (Map.Entry<String, List<ConcurrentMessageListenerContainer<Object, Object>>> entry : containersByGroup.entrySet()) {
            List<ConcurrentMessageListenerContainer<Object, Object>> containers = entry.getValue();
            // 동시성 3(결정 212)이면 인스턴스 둘 × 소비자 셋 = 6 이 파티션 3을 나눠, 어떤 인스턴스는 하나도 못 받을 수 있다.
            await("그룹 " + entry.getKey() + " 파티션 배정", () -> assigned(containers) == PARTITIONS);
        }
    }

    private static int partitionsOf(ConcurrentMessageListenerContainer<Object, Object> container) {
        return container.getAssignedPartitions() == null ? 0 : container.getAssignedPartitions().size();
    }

    private static int assigned(List<ConcurrentMessageListenerContainer<Object, Object>> containers) {
        return containers.stream().mapToInt(KafkaContractTest::partitionsOf).sum();
    }

    @Override
    protected void publish(OutboundMessage message) {
        publisher.publish(message).orTimeout(10, TimeUnit.SECONDS).join();
    }
}
