package com.stove.common.messaging.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import com.stove.common.event.EventHandler;
import com.stove.common.event.EventSubscriber;
import com.stove.common.event.KeyQuarantine;
import com.stove.common.event.OutboundMessage;
import com.stove.common.messaging.contract.MessagingContractTest;
import com.stove.common.testcontainers.InfraContainers;
import java.lang.annotation.Annotation;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.ConsumerGroupState;
import org.apache.kafka.common.errors.GroupIdNotFoundException;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.common.utils.Utils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
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

    /** Boot 가 만드는 기본 리스너 팩토리. 격리 없는 앱(Store)의 구독이 쓴다. */
    @Autowired
    @Qualifier("kafkaListenerContainerFactory")
    ConcurrentKafkaListenerContainerFactory<Object, Object> defaultFactory;

    private DefaultKafkaProducerFactory<String, String> producers;
    /** 그룹 상태 조회용. 테스트마다 하나를 만들어 대기 조건이 평가될 때마다 다시 쓴다(PR #223 R5). */
    private Admin admin;
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
        admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaContainer.getBootstrapServers()));
        publisher = new KafkaMessagePublisher(new KafkaTemplate<>(producers));
    }

    @Override
    protected void closeAdapter() {
        containersByGroup.clear();
        producers.destroy();
        admin.close();
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
        assertThat(single.getConcurrency()).as("oneAtATime 도 같은 동시성").isEqualTo(KafkaListenerDefaults.PARTITIONS);
        assertThat(defaultFactory.createContainer(topic).getConcurrency())
                .as("Boot 기본 팩토리(격리 없는 Store 경로)도 같은 동시성").isEqualTo(KafkaListenerDefaults.PARTITIONS);
    }

    /**
     * 결정 212의 동작: 한 파티션의 키가 제자리 재시도(1·2·4초)에 걸려 있는 동안 다른 파티션의 키는 진행한다.
     * 동시성 1이면 소비자 하나가 모든 파티션을 맡아, 재시도 대기 동안 다른 파티션 레코드도 그 대기가 끝날 때까지 늦는다.
     * 확정적으로 보려고 붙잡힌 키의 세 번째 시도가 시작된 뒤(다음 대기 4초) 다른 파티션 메시지를 보낸다(PR #223 R3·R4).
     */
    @Test
    void whileOnePartitionRetriesAnotherPartitionKeepsFlowing() {
        String stuck = keyOnPartition(0);
        String flowing = keyOnPartition(1);
        List<String> received = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger stuckAttempts = new AtomicInteger();
        AtomicInteger stuckAttemptsWhenFlowingArrived = new AtomicInteger(-1);
        start(group, quarantine, message -> {
            if (message.key().equals(stuck)) {
                stuckAttempts.incrementAndGet();
                throw new IllegalStateException("boom");
            }
            stuckAttemptsWhenFlowingArrived.compareAndSet(-1, stuckAttempts.get());
            received.add(message.key());
        }, List.of());
        awaitSubscriptionsReady();

        send(stuck);
        await("붙잡힌 키의 세 번째 시도(다음 대기 4초)", () -> stuckAttempts.get() >= 3);
        long sentAt = System.nanoTime();
        send(flowing);
        await("다른 파티션 키 수신", () -> received.contains(flowing));
        long tookMs = Duration.ofNanos(System.nanoTime() - sentAt).toMillis();

        assertThat(stuckAttemptsWhenFlowingArrived.get()).as("받을 때 앞 키는 아직 재시도 중(마지막 시도 전)이다")
                .isEqualTo(3);
        assertThat(tookMs).as("앞 키의 남은 재시도 대기(4초)를 기다리지 않는다").isLessThan(2_000);
    }


    /** Kafka 기본 파티셔너(키 바이트 murmur2)로 {@code partition} 에 가는 키를 고른다. */
    private static String keyOnPartition(int partition) {
        return keyOnPartition(partition, "K-" + partition + "-");
    }

    private static String keyOnPartition(int partition, String prefix) {
        for (int i = 0; ; i++) {
            String key = prefix + i;
            if (Utils.toPositive(Utils.murmur2(key.getBytes(StandardCharsets.UTF_8))) % PARTITIONS == partition) {
                return key;
            }
        }
    }

    /** 그룹마다 인스턴스들이 파티션을 모두 나눠 가질 때까지(리밸런스 끝) 기다린다. 도중에 보내면 재배달이 섞인다. */
    @Override
    protected void awaitSubscriptionsReady() {
        for (Map.Entry<String, List<ConcurrentMessageListenerContainer<Object, Object>>> entry : containersByGroup.entrySet()) {
            List<ConcurrentMessageListenerContainer<Object, Object>> containers = entry.getValue();
            // 동시성 3(결정 212)이면 인스턴스 둘 × 소비자 셋 = 6 이 파티션 3을 나눠, 어떤 인스턴스는 하나도 못 받을 수 있다.
            // 그래서 배정 합계만으로는 둘째 인스턴스가 아직 안 들어온 상태와 구별되지 않는다 — 그룹이 Stable 이고
            // 멤버 수가 (인스턴스 × 동시성) 인지도 본다(PR #223 R1).
            int members = containers.stream().mapToInt(ConcurrentMessageListenerContainer::getConcurrency).sum();
            await("그룹 " + entry.getKey() + " 멤버 " + members + " 안정·파티션 배정",
                    () -> assigned(containers) == PARTITIONS && stableWith(entry.getKey(), members));
        }
    }

    /** 그룹이 Stable 이고 멤버가 {@code members} 명인가. 아직 그룹이 없거나 응답이 늦으면 false, 그 밖의 오류는 그대로 던진다. */
    private boolean stableWith(String inGroup, int members) {
        try {
            ConsumerGroupDescription description = admin.describeConsumerGroups(List.of(inGroup))
                    .describedGroups().get(inGroup).get(2, TimeUnit.SECONDS);
            return description.state() == ConsumerGroupState.STABLE && description.members().size() == members;
        } catch (TimeoutException slow) {
            return false;
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof GroupIdNotFoundException) {
                return false;
            }
            throw new IllegalStateException("소비 그룹을 조회하지 못했다: " + inGroup, failed.getCause());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
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
