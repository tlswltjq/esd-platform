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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
     * 결정 212의 동작: 한 파티션의 키가 제자리 재시도에 걸려 소비자 스레드를 붙잡고 있는 동안 다른 파티션의 키는 진행한다.
     * 동시성 1이면 소비자 스레드 하나가 모든 파티션을 맡아, 그 스레드가 붙잡혀 있는 동안(재시도 대기로 잠들든 처리 중이든)
     * 다른 파티션 레코드를 처리하지 못한다. 시간 문턱 대신 동기화 지점으로 본다: 붙잡힌 키의 두 번째 시도(첫 재시도)가
     * 다른 파티션 메시지 처리 신호를 기다리며 스레드를 붙든다. 동시성 3이면 다른 소비자가 처리해 신호가 오고,
     * 동시성 1이면 같은 스레드라 신호가 끝내 오지 않는다(PR #223 R3·R4·R8·R11).
     *
     * <p>재시도 대기 자체를 붙잡지는 않는다(R12, 부분 반영). 운영 팩토리에 대기 주입 통로를 여는 대신, 스레드가 붙잡히는
     * 방식(대기로 잠들든 처리 중이든)과 무관하게 다른 소비자 스레드가 진행하는지를 본다. {@code DefaultErrorHandler} 의
     * 재시도 대기는 그 레코드를 맡은 소비자 스레드만 재운다(Spring Kafka 문서 "DefaultErrorHandler").
     */
    @Test
    void whileOnePartitionRetriesAnotherPartitionKeepsFlowing() throws InterruptedException {
        String stuck = keyOnPartition(0);
        String flowing = keyOnPartition(1);
        AtomicInteger stuckAttempts = new AtomicInteger();
        CountDownLatch retryHolding = new CountDownLatch(1);
        CountDownLatch flowed = new CountDownLatch(1);
        CountDownLatch flowingSent = new CountDownLatch(1);
        AtomicReference<String> held = new AtomicReference<>("붙잡지 못함");
        CountDownLatch holdEnded = new CountDownLatch(1);
        start(group, quarantine, message -> {
            if (message.key().equals(stuck)) {
                if (stuckAttempts.incrementAndGet() == 2) {
                    retryHolding.countDown();
                    held.set(holdUntilTheOtherPartitionFlows(flowingSent, flowed));
                    holdEnded.countDown();
                }
                throw new IllegalStateException("boom");
            }
            flowed.countDown();
        }, List.of());
        awaitSubscriptionsReady();

        send(stuck);
        assertThat(retryHolding.await(timeout().toSeconds(), TimeUnit.SECONDS)).as("붙잡힌 키의 첫 재시도가 시작된다").isTrue();
        try {
            send(flowing);
        } finally {
            // 발행이 성공하든 실패하든(publish 는 10초 한도) 반드시 알린다 — 그래서 붙잡은 쪽은 이 신호를 한도 없이 기다린다.
            flowingSent.countDown();
        }
        // 붙잡은 쪽의 처리 대기 한도는 발행을 마친 뒤부터 잰다 — 그 전의 테스트 스레드 지연은 판정에 들어가지 않는다(R8·R11).
        assertThat(holdEnded.await(timeout().toSeconds() + 10, TimeUnit.SECONDS)).as("붙잡은 재시도가 끝난다").isTrue();

        assertThat(held.get()).as("다른 파티션은 붙잡힌 키가 스레드를 쥔 동안 처리된다(동시성 1이면 같은 스레드라 처리되지 않는다)")
                .isEqualTo("처리됨");
    }

    /** 발행이 끝나길(테스트가 finally 로 반드시 알린다) 기다린 뒤, 그때부터 {@code timeout()} 안에 다른 파티션이 처리되는지 본다. 결과를 이유와 함께 돌려준다. */
    private String holdUntilTheOtherPartitionFlows(CountDownLatch flowingSent, CountDownLatch flowed) {
        try {
            flowingSent.await();
            return flowed.await(timeout().toSeconds(), TimeUnit.SECONDS) ? "처리됨" : "발행 뒤 한도 안에 처리되지 않음";
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return "중단됨";
        }
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
