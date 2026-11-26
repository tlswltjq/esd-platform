package com.stove.common.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.stove.common.contract.EventHeaders;
import com.stove.common.messaging.kafka.KeyQuarantine;
import com.stove.common.messaging.kafka.ListenerContainerFactories;
import com.stove.common.messaging.kafka.StoppedKeyInterceptor;
import com.stove.common.testcontainers.InfraContainers;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.ConsumerGroupState;
import org.apache.kafka.common.errors.GroupIdNotFoundException;
import org.apache.kafka.common.utils.Utils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.MessageListener;
import org.testcontainers.kafka.ConfluentKafkaContainer;

/**
 * Kafka 수신의 전달 보장(결정 155·202·206·212의 동작)을 실제 Kafka·MySQL 로 본다(결정 227).
 *
 * <p>컨테이너는 운영 팩토리로 만든다: 선택 없는 리스너는 Boot 기본 팩토리({@code kafkaListenerContainerFactory} — 공용 오류 처리기
 * 1·2·4초 재시도 뒤 소비 모듈 DB 격리, 멈춘 키 인터셉터), 재시도 제외·격리처 바꾸기는 앱 config 와 같은
 * {@link ListenerContainerFactories}. {@code @KafkaListener} 등록 단계만 건너뛴다(그룹·토픽을 테스트마다 바꾼다).
 *
 * <p>보장:
 * <ol>
 *   <li>같은 키는 발행 순서대로, 한 번씩, 한 번에 하나씩 처리된다. 앞 처리를 붙잡아 둔 동안 뒤 레코드는 어느 인스턴스에서도 시작되지 않는다.</li>
 *   <li>같은 그룹의 인스턴스가 둘이어도 한 레코드는 한 인스턴스만 처리하고, 같은 키를 두 인스턴스가 동시에 처리하지 않는다.</li>
 *   <li>다른 그룹은 각자 모든 레코드를 받는다(팬아웃).</li>
 *   <li>처리 실패는 제자리에서 재시도(최초 1회 + 재시도 {@value #RETRIES}회)한 뒤 격리하고 그 키만 멈춘다.
 *       멈춘 키의 뒤 레코드는 처리하지 않고 격리 뒤에 쌓는다. 다른 키는 계속 흐른다.</li>
 *   <li>재시도 제외 예외는 첫 실패에 격리한다.</li>
 *   <li>격리 기록이 실패하면 그 레코드를 붙잡아, 같은 키의 뒤 레코드가 추월하지 못한다.</li>
 *   <li>재시도 도중 성공하면 격리하지 않고, 같은 키의 뒤 레코드는 그 성공 뒤에 처리된다.</li>
 *   <li>한 파티션이 재시도로 붙잡혀 있어도 다른 파티션은 흐른다(리스너 동시성 = 파티션 수).</li>
 * </ol>
 *
 * <p>건수 단언은 목표 건수에 닿은 뒤 {@link #QUIET} 동안 더 오는 것이 없는지 보고 한다 — 닿자마자 세면 직후의 중복 전달을 놓친다.
 */
@SpringBootTest(properties = {"stove.outbox.relay-enabled=false", "spring.kafka.consumer.auto-offset-reset=earliest"})
@Import({InfraContainers.MySql.class, InfraContainers.Kafka.class})
class KafkaDeliveryIntegrationTest {

    /** 최초 배달 뒤 재시도 횟수({@code ConsumerRetryPolicy.MAX_RETRIES}). */
    private static final int RETRIES = 3;
    /** 테스트 토픽 파티션 수. 리스너 동시성 기본값과 같다(결정 212). */
    private static final int PARTITIONS = 3;
    /**
     * 순서·동시성 시험에서 레코드마다 처리 중에 머무는 시간. 같은 키의 다음 레코드가 발행 간격(한 건씩 확인을 기다림)보다
     * 이 시간 안에 도착해야 겹쳐 처리하는 구현이 드러난다 — 20ms 로는 비동기로 넘기는 변이도 통과했다(PR #219 R1 변이 확인).
     */
    private static final Duration HOLD = Duration.ofMillis(150);
    /** 재시도 1·2·4초에 격리 기록 실패 시 재시도 한 바퀴가 다시 돈다. 리밸런스도 몇 초 걸린다. */
    private static final Duration TIMEOUT = Duration.ofSeconds(90);
    /** 리밸런스 직후 커밋 전 레코드가 다시 오는 경우까지 지켜본다. */
    private static final Duration QUIET = Duration.ofSeconds(4);

    @Autowired
    ConfluentKafkaContainer kafkaContainer;

    @Autowired
    KeyQuarantine quarantine;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    KafkaTemplate<String, String> kafkaTemplate;

    /** Boot 기본 리스너 팩토리. 재시도 선택 없는 운영 리스너가 쓴다. */
    @Autowired
    @Qualifier("kafkaListenerContainerFactory")
    ConcurrentKafkaListenerContainerFactory<Object, Object> defaultFactory;

    @Autowired
    ConcurrentKafkaListenerContainerFactoryConfigurer configurer;

    @Autowired
    ConsumerFactory<Object, Object> consumers;

    @Autowired
    ListenerContainerFactories factories;

    private String group;
    private String topic;
    private Admin admin;
    private final List<ConcurrentMessageListenerContainer<Object, Object>> started = new ArrayList<>();
    private final Map<String, List<ConcurrentMessageListenerContainer<Object, Object>>> containersByGroup = new LinkedHashMap<>();

    @BeforeEach
    void prepare() throws Exception {
        group = "delivery-" + UUID.randomUUID();
        topic = "delivery." + UUID.randomUUID().toString().substring(0, 8);
        admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaContainer.getBootstrapServers()));
        admin.createTopics(List.of(new NewTopic(topic, PARTITIONS, (short) 1))).all().get(30, TimeUnit.SECONDS);
    }

    @AfterEach
    void stop() {
        // 하나가 실패해도 나머지를 모두 닫는다. 살아남은 소비자가 다음 테스트의 공유 표·브로커에 끼어들지 않게.
        for (ConcurrentMessageListenerContainer<Object, Object> container : started) {
            container.stop();
        }
        started.clear();
        containersByGroup.clear();
        admin.close();
    }

    // ── 보장 ───────────────────────────────────────────────────────────

    @Test
    void 같은_키는_발행_순서대로_한_번씩_한_번에_하나씩_처리된다() {
        PerKeyConcurrency concurrency = new PerKeyConcurrency();
        Recording handler = new Recording(concurrency, HOLD);
        start(group, handler);
        awaitSubscriptionsReady();
        Map<String, List<String>> sentByKey = new LinkedHashMap<>();
        for (int round = 0; round < 5; round++) {
            for (int k = 0; k < 6; k++) {
                String key = "K-" + k;
                sentByKey.computeIfAbsent(key, ignored -> new ArrayList<>()).add(send(key));
            }
        }

        await("30건 수신", () -> handler.ids().size() >= 30);
        settle();

        assertThat(handler.ids()).as("목표 뒤 관찰 구간에도 더 오지 않는다(중복 없음)").hasSize(30);
        for (Map.Entry<String, List<String>> sent : sentByKey.entrySet()) {
            assertThat(handler.idsOf(sent.getKey())).as("키 %s 는 보낸 순서대로 한 번씩", sent.getKey())
                    .containsExactlyElementsOf(sent.getValue());
        }
        assertThat(concurrency.max()).as("같은 키를 동시에 처리한 최대 수").isEqualTo(1);
        assertThat(handler.firstPayload().replace(" ", "")).startsWith("{\"key\":");
    }

    @Test
    void 같은_그룹_인스턴스_둘은_한_레코드를_함께_처리하지_않고_같은_키를_동시에_처리하지_않는다() {
        PerKeyConcurrency concurrency = new PerKeyConcurrency();
        Recording first = new Recording(concurrency, HOLD);
        Recording second = new Recording(concurrency, HOLD);
        start(group, first);
        start(group, second);
        awaitSubscriptionsReady();
        List<String> sent = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            sent.add(send("K-" + (i % 4)));
        }

        await("20건 수신", () -> first.ids().size() + second.ids().size() >= 20);
        settle();

        List<String> all = new ArrayList<>(first.ids());
        all.addAll(second.ids());
        assertThat(all).as("같은 그룹에서는 한 레코드를 한 인스턴스만 처리한다").containsExactlyInAnyOrderElementsOf(sent);
        assertThat(concurrency.max()).as("두 인스턴스를 합쳐 같은 키를 동시에 처리한 최대 수").isEqualTo(1);
    }

    /**
     * 한계(PR #219 R9, 부분 반영): 뒤 레코드가 poll 로 소비자에 들어온 시점은 관찰하지 않는다. 관찰 구간 안에 뒤 레코드가 전달되지
     * 않으면 동시 처리 결함이 있어도 통과할 수 있다. 수신부가 처리를 별도 스레드로 넘기는 변이는 {@code expected: 0 but was: 1}로 잡는다.
     */
    @Test
    void 같은_키의_앞_처리가_끝나기_전에는_어느_인스턴스도_뒤_레코드를_시작하지_않는다() throws InterruptedException {
        String key = "K-" + UUID.randomUUID();
        Gate gate = new Gate(key);
        PerKeyConcurrency concurrency = new PerKeyConcurrency();
        Recording first = new Recording(concurrency, Duration.ZERO);
        Recording second = new Recording(concurrency, Duration.ZERO);
        first.holdFirstRecordOf(gate);
        second.holdFirstRecordOf(gate);
        start(group, first);
        start(group, second);
        awaitSubscriptionsReady();

        String held = send(key);
        String behind;
        try {
            assertThat(gate.entered.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS)).as("앞 레코드 처리가 시작됐다").isTrue();
            behind = send(key);
            settle();

            assertThat(first.attempts(behind) + second.attempts(behind))
                    .as("앞 처리가 붙잡혀 있는 동안 같은 키의 뒤 레코드는 어느 인스턴스에서도 시작되지 않는다").isZero();
        } finally {
            gate.open();
        }
        await("두 건 수신", () -> first.ids().size() + second.ids().size() >= 2);
        settle();

        List<String> all = new ArrayList<>(first.ids());
        all.addAll(second.ids());
        assertThat(all).containsExactlyInAnyOrder(held, behind);
        assertThat(concurrency.max()).isEqualTo(1);
    }

    @Test
    void 다른_그룹은_각자_모든_레코드를_받는다() {
        Recording mine = new Recording();
        Recording theirs = new Recording();
        String otherGroup = group + "-other";
        start(group, mine);
        start(otherGroup, theirs);
        awaitSubscriptionsReady();
        List<String> sent = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            sent.add(send("K-" + (i % 3)));
        }

        await("두 그룹 모두 10건", () -> mine.ids().size() >= 10 && theirs.ids().size() >= 10);
        settle();

        assertThat(mine.ids()).containsExactlyInAnyOrderElementsOf(sent);
        assertThat(theirs.ids()).containsExactlyInAnyOrderElementsOf(sent);
    }

    @Test
    void 실패는_제자리_재시도_뒤_격리하고_그_키만_멈춘다() {
        String broken = "K-broken-" + UUID.randomUUID();
        String healthy = "K-healthy-" + UUID.randomUUID();
        Recording handler = new Recording();
        handler.failKey = broken;
        start(group, handler);
        awaitSubscriptionsReady();

        String failing = send(broken);
        String behind = send(broken);
        String other = send(healthy);

        await("격리 2건", () -> quarantinedCount(group) >= 2);
        await("다른 키 수신", () -> handler.ids().contains(other));
        settle();

        assertThat(handler.attempts(failing)).as("최초 1회 + 재시도 %d회", RETRIES).isEqualTo(1 + RETRIES);
        assertThat(handler.attempts(behind)).as("멈춘 키의 뒤 레코드는 처리하지 않고 보관").isZero();
        assertThat(quarantine.stopped(group, topic, broken)).isTrue();
        assertThat(quarantine.stopped(group, topic, healthy)).isFalse();
        assertThat(keyStoppedEventId(group)).isEqualTo(behind);
    }

    @Test
    void 재시도_제외_예외는_첫_실패에_격리한다() {
        String key = "K-" + UUID.randomUUID();
        Recording handler = new Recording();
        handler.rejectKey = key;
        start(group, factories.noRetryFor(Rejected.class), handler);
        awaitSubscriptionsReady();

        String rejected = send(key);

        await("격리", () -> quarantine.stopped(group, topic, key));
        settle();
        assertThat(handler.attempts(rejected)).isEqualTo(1);
    }

    @Test
    void 격리_기록이_실패하면_레코드를_붙잡아_같은_키_뒤_레코드가_추월하지_못한다() {
        String key = "K-" + UUID.randomUUID();
        Recording handler = new Recording();
        handler.failKey = key;
        FlakyQuarantine storage = new FlakyQuarantine(quarantine, 2);
        start(group, withQuarantine(storage).standard(), handler);
        awaitSubscriptionsReady();

        String first = send(key);
        String second = send(key);

        await("두 건 모두 보관", () -> quarantinedCount(group) >= 2);
        settle();
        assertThat(storage.failures()).as("앞 레코드의 보관이 두 번 실패했다").isEqualTo(2);
        assertThat(handler.attempts(second)).as("앞 레코드가 붙잡혀 있는 동안 뒤 레코드는 처리되지 않고, 키 정지 뒤 보관된다")
                .isZero();
        assertThat(jdbc.queryForObject(
                "SELECT event_id FROM consumer_quarantine WHERE consumer_group = ? AND failure <> 'KEY_STOPPED'",
                String.class, group)).isEqualTo(first);
        assertThat(keyStoppedEventId(group)).isEqualTo(second);
    }

    @Test
    void 재시도_도중_성공하면_격리하지_않고_같은_키_뒤_레코드는_그_성공_뒤에_처리된다() {
        String key = "K-" + UUID.randomUUID();
        Recording handler = new Recording(null, Duration.ZERO);
        handler.failFirstRecordOf(key, 2);
        start(group, handler);
        awaitSubscriptionsReady();

        String first = send(key);
        String behind = send(key);

        await("두 건 수신", () -> handler.ids().size() >= 2);
        settle();

        assertThat(handler.ids()).as("앞 레코드가 성공한 뒤 뒤 레코드").containsExactly(first, behind);
        assertThat(handler.attempts(first)).as("두 번 실패 뒤 세 번째에 성공").isEqualTo(3);
        assertThat(handler.firstAttemptIndex(behind)).as("뒤 레코드는 앞 레코드의 마지막 시도 뒤에 처음 시도된다")
                .isGreaterThan(handler.lastAttemptIndex(first));
        assertThat(quarantinedCount(group)).as("재시도 안에서 성공하면 격리하지 않는다").isZero();
        assertThat(quarantine.stopped(group, topic, key)).isFalse();
    }

    /**
     * 결정 212의 동작: 한 파티션의 키가 제자리 재시도에 걸려 소비자 스레드를 붙잡고 있는 동안 다른 파티션의 키는 진행한다.
     * 동시성 1이면 소비자 스레드 하나가 모든 파티션을 맡아, 그 스레드가 붙잡혀 있는 동안 다른 파티션 레코드를 처리하지 못한다.
     * 시간 문턱 대신 동기화 지점으로 본다: 붙잡힌 키의 두 번째 시도(첫 재시도)가 다른 파티션 레코드 처리 신호를 기다리며 스레드를 붙든다
     * (PR #223 R3·R4·R8·R11). {@code DefaultErrorHandler} 의 재시도 대기는 그 레코드를 맡은 소비자 스레드만 재운다.
     */
    @Test
    void 한_파티션이_재시도로_붙잡혀_있어도_다른_파티션은_흐른다() throws InterruptedException {
        String stuck = keyOnPartition(0);
        String flowing = keyOnPartition(1);
        AtomicInteger stuckAttempts = new AtomicInteger();
        CountDownLatch retryHolding = new CountDownLatch(1);
        CountDownLatch flowed = new CountDownLatch(1);
        CountDownLatch flowingSent = new CountDownLatch(1);
        AtomicReference<String> held = new AtomicReference<>("붙잡지 못함");
        CountDownLatch holdEnded = new CountDownLatch(1);
        start(group, defaultFactory, record -> {
            if (stuck.equals(record.key())) {
                if (stuckAttempts.incrementAndGet() == 2) {
                    retryHolding.countDown();
                    held.set(holdUntilTheOtherPartitionFlows(flowingSent, flowed));
                    holdEnded.countDown();
                }
                throw new IllegalStateException("boom");
            }
            flowed.countDown();
        });
        awaitSubscriptionsReady();

        try {
            send(stuck);
            assertThat(retryHolding.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS)).as("붙잡힌 키의 첫 재시도가 시작된다").isTrue();
            send(flowing);
        } finally {
            // 어느 단계에서 실패하든(발행은 10초 한도) 반드시 알린다 — 그래서 붙잡은 쪽은 이 신호를 한도 없이 기다린다(R13).
            flowingSent.countDown();
        }
        // 붙잡은 쪽의 처리 대기 한도는 발행을 마친 뒤부터 잰다(R8·R11).
        assertThat(holdEnded.await(TIMEOUT.toSeconds() + 10, TimeUnit.SECONDS)).as("붙잡은 재시도가 끝난다").isTrue();

        assertThat(held.get()).as("다른 파티션은 붙잡힌 키가 스레드를 쥔 동안 처리된다(동시성 1이면 같은 스레드라 처리되지 않는다)")
                .isEqualTo("처리됨");
    }

    /** 운영 팩토리의 가져오는 건수·동시성: {@code oneAtATime} 은 한 번에 한 건, 모든 팩토리의 동시성 = 파티션 수(R2·결정 212). */
    @Test
    void 운영_팩토리는_동시성이_파티션_수이고_oneAtATime_은_한_건씩_가져온다() {
        ConcurrentMessageListenerContainer<Object, Object> single = factories.oneAtATime().createContainer(topic);
        ConcurrentMessageListenerContainer<Object, Object> batch = defaultFactory.createContainer(topic);

        assertThat(single.getContainerProperties().getKafkaConsumerProperties()
                .getProperty(ConsumerConfig.MAX_POLL_RECORDS_CONFIG)).isEqualTo("1");
        assertThat(batch.getContainerProperties().getKafkaConsumerProperties()
                .getProperty(ConsumerConfig.MAX_POLL_RECORDS_CONFIG)).isNull();
        assertThat(single.getCommonErrorHandler()).as("격리 오류 처리기가 운영 팩토리에서 붙는다").isNotNull();
        assertThat(batch.getConcurrency()).as("결정 212: 리스너 동시성 기본값 = 파티션 수").isEqualTo(PARTITIONS);
        assertThat(single.getConcurrency()).as("oneAtATime 도 같은 동시성").isEqualTo(PARTITIONS);
    }

    /**
     * S5 독 레코드 1건(결정 210 반대 시나리오). 계속 실패하는 키 A 뒤에 같은 파티션의 키 B 와 다른 파티션의 키 C 를 보내고,
     * 운영 재시도 간격(1·2·4초)에서 B·C 가 처음 처리되기까지의 시간을 잰다. 지연은 측정값이라 단언하지 않고
     * {@code build/s5-poison/} 에 JSON 으로 남긴다. 단언은 "A 는 격리되고, B·C 는 결국 처리된다"뿐이다.
     */
    @Test
    void S5_독_레코드_뒤_같은_파티션과_다른_파티션의_지연을_잰다() throws IOException {
        String poison = "P-" + UUID.randomUUID();
        String same = keyOnPartition(partitionOf(poison), "S-same-");
        String other = keyOnPartition((partitionOf(poison) + 1) % PARTITIONS, "S-other-");
        Recording handler = new Recording();
        handler.failKey = poison;
        start(group, handler);
        awaitSubscriptionsReady();

        long poisonSent = System.nanoTime();
        send(poison);
        long behindSent = System.nanoTime();
        String behind = send(same);
        long asideSent = System.nanoTime();
        String aside = send(other);

        await("독 레코드 격리", () -> quarantine.stopped(group, topic, poison));
        long quarantined = System.nanoTime();
        await("같은 파티션 뒤 레코드 수신", () -> handler.ids().contains(behind));
        await("다른 파티션 레코드 수신", () -> handler.ids().contains(aside));

        String json = String.format(Locale.ROOT,
                "{\"adapter\":\"%s\",\"retry_delays_ms\":[1000,2000,4000],\"poison_quarantined_ms\":%.1f,"
                        + "\"same_partition_first_attempt_ms\":%.1f,\"other_partition_first_attempt_ms\":%.1f,"
                        + "\"recorded_at\":\"%s\"}",
                getClass().getSimpleName(), millis(quarantined - poisonSent),
                millis(handler.firstAttemptNanos(behind) - behindSent),
                millis(handler.firstAttemptNanos(aside) - asideSent), Instant.now());
        Path directory = Path.of("build", "s5-poison");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve(getClass().getSimpleName() + "-" + System.currentTimeMillis() + ".json"), json);
        assertThat(handler.ids()).contains(behind, aside);
    }

    // ── 구독·발행 ─────────────────────────────────────────────────────

    /** 운영 기본 팩토리로 구독을 시작한다. 같은 그룹으로 여러 번 부르면 같은 그룹의 인스턴스가 늘어난다. */
    private void start(String inGroup, Recording handler) {
        start(inGroup, defaultFactory, handler);
    }

    private void start(String inGroup, ConcurrentKafkaListenerContainerFactory<Object, Object> factory, RecordHandler handler) {
        ConcurrentMessageListenerContainer<Object, Object> container = factory.createContainer(topic);
        container.getContainerProperties().setGroupId(inGroup);
        container.getContainerProperties().setMessageListener(listener(handler));
        container.start();
        started.add(container);
        containersByGroup.computeIfAbsent(inGroup, ignored -> new ArrayList<>()).add(container);
    }

    /** 리스너 컨테이너는 문자열 레코드만 받는다(Boot 의 StringDeserializer 설정). 팩토리 제네릭이 Object 라 좁힌다. */
    @SuppressWarnings("unchecked")
    private static MessageListener<Object, Object> listener(RecordHandler handler) {
        return record -> handler.receive((ConsumerRecord<String, String>) (ConsumerRecord<?, ?>) record);
    }

    /** 격리처를 바꾼 운영 팩토리 묶음(격리 기록 실패 흉내). 격리처 말고는 앱 자동 구성과 같다. */
    private ListenerContainerFactories withQuarantine(KeyQuarantine storage) {
        return new ListenerContainerFactories(() -> configurer, () -> consumers,
                ListenerContainerFactories.recoverer(kafkaTemplate, null, storage), new StoppedKeyInterceptor(storage));
    }

    private String send(String key) {
        String eventId = UUID.randomUUID().toString();
        ProducerRecord<String, String> record = new ProducerRecord<>(topic, key, "{\"key\":\"" + key + "\"}");
        record.headers().add(EventHeaders.EVENT_ID, eventId.getBytes(StandardCharsets.UTF_8));
        record.headers().add(EventHeaders.EVENT_TYPE, "DeliveryHappened".getBytes(StandardCharsets.UTF_8));
        record.headers().add(EventHeaders.OCCURRED_AT, Instant.now().toString().getBytes(StandardCharsets.UTF_8));
        kafkaTemplate.send(record).orTimeout(10, TimeUnit.SECONDS).join();
        return eventId;
    }

    /** 그룹마다 인스턴스들이 파티션을 모두 나눠 가질 때까지(리밸런스 끝) 기다린다. 도중에 보내면 재배달이 섞인다. */
    private void awaitSubscriptionsReady() {
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

    private static int assigned(List<ConcurrentMessageListenerContainer<Object, Object>> containers) {
        int total = 0;
        for (ConcurrentMessageListenerContainer<Object, Object> container : containers) {
            total += container.getAssignedPartitions() == null ? 0 : container.getAssignedPartitions().size();
        }
        return total;
    }

    /** Kafka 기본 파티셔너(키가 있을 때)와 같은 계산: murmur2(키 바이트) 양수화 % 파티션 수. */
    private static int partitionOf(String key) {
        return Utils.toPositive(Utils.murmur2(key.getBytes(StandardCharsets.UTF_8))) % PARTITIONS;
    }

    private static String keyOnPartition(int partition) {
        return keyOnPartition(partition, "K-" + partition + "-");
    }

    private static String keyOnPartition(int partition, String prefix) {
        for (int i = 0; ; i++) {
            String key = prefix + i;
            if (partitionOf(key) == partition) {
                return key;
            }
        }
    }

    /** 발행이 끝나길(테스트가 finally 로 반드시 알린다) 기다린 뒤, 그때부터 한도 안에 다른 파티션이 처리되는지 본다. */
    private static String holdUntilTheOtherPartitionFlows(CountDownLatch flowingSent, CountDownLatch flowed) {
        try {
            flowingSent.await();
            return flowed.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS) ? "처리됨" : "발행 뒤 한도 안에 처리되지 않음";
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return "중단됨";
        }
    }

    private int quarantinedCount(String inGroup) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM consumer_quarantine WHERE consumer_group = ?",
                Integer.class, inGroup);
        return count == null ? 0 : count;
    }

    private String keyStoppedEventId(String inGroup) {
        return jdbc.queryForObject(
                "SELECT event_id FROM consumer_quarantine WHERE consumer_group = ? AND failure = 'KEY_STOPPED'",
                String.class, inGroup);
    }

    private static void await(String what, BooleanSupplier condition) {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("기다렸지만 이뤄지지 않았다: " + what);
            }
            sleep(Duration.ofMillis(50));
        }
    }

    /** 목표에 닿은 뒤 {@link #QUIET} 동안 기다린다. 그 뒤에 정확한 건수를 단언한다. */
    private static void settle() {
        sleep(QUIET);
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private static double millis(long nanos) {
        return nanos / 1_000_000.0;
    }

    // ── 대역 ──────────────────────────────────────────────────────────

    /** 테스트 리스너가 받는 레코드 한 건. */
    @FunctionalInterface
    private interface RecordHandler {
        void receive(ConsumerRecord<String, String> record);
    }

    /** 처음 몇 번의 보관 기록을 실패시킨다(격리 DB 가 잠깐 안 되는 상황). */
    private static final class FlakyQuarantine implements KeyQuarantine {
        private final KeyQuarantine delegate;
        private final AtomicInteger remainingFailures;
        private final AtomicInteger failures = new AtomicInteger();

        FlakyQuarantine(KeyQuarantine delegate, int failures) {
            this.delegate = delegate;
            this.remainingFailures = new AtomicInteger(failures);
        }

        int failures() {
            return failures.get();
        }

        @Override
        public boolean stopped(String consumerGroup, String topic, String key) {
            return delegate.stopped(consumerGroup, topic, key);
        }

        @Override
        public void quarantine(String consumerGroup, ConsumerRecord<String, String> record, Throwable failure) {
            if (remainingFailures.getAndDecrement() > 0) {
                failures.incrementAndGet();
                throw new IllegalStateException("격리 DB 잠깐 안 됨");
            }
            delegate.quarantine(consumerGroup, record, failure);
        }
    }

    /** 재시도 제외로 선언하는 예외. */
    private static final class Rejected extends RuntimeException {
        Rejected() {
            super("rejected");
        }
    }

    /** 같은 키의 첫 레코드 처리를 붙잡아 두는 문. 붙잡힌 동안 같은 키의 다른 레코드가 시작되는지 본다(PR #219 R1). */
    private static final class Gate {
        private final String key;
        private final AtomicReference<String> held = new AtomicReference<>();
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        Gate(String key) {
            this.key = key;
        }

        boolean claim(String eventId) {
            // 붙잡은 레코드는 재전달·재시도돼도 문이 열릴 때까지 다시 붙잡는다(PR #219 R7). 열린 뒤에는 await가 바로 끝난다.
            return held.compareAndSet(null, eventId) || eventId.equals(held.get());
        }

        void open() {
            release.countDown();
        }
    }

    /** 같은 키를 동시에 처리 중인 수와 그 최댓값. 여러 인스턴스의 핸들러가 하나를 나눠 쓴다. */
    private static final class PerKeyConcurrency {
        private final Map<String, AtomicInteger> inFlight = new ConcurrentHashMap<>();
        private final AtomicInteger max = new AtomicInteger();

        void enter(String key) {
            int now = inFlight.computeIfAbsent(key, ignored -> new AtomicInteger()).incrementAndGet();
            max.accumulateAndGet(now, Math::max);
        }

        void exit(String key) {
            inFlight.get(key).decrementAndGet();
        }

        int max() {
            return max.get();
        }
    }

    /**
     * 받은 레코드와 시도를 기록한다. {@code failKey} 는 늘 실패, {@code rejectKey} 는 재시도 제외 예외,
     * {@link #failFirstRecordOf} 는 그 키의 첫 레코드만 정한 횟수 실패한 뒤 성공한다.
     * {@code hold} 만큼 처리 중에 머물러, 같은 키를 겹쳐 처리하면 {@link PerKeyConcurrency} 에 드러나게 한다.
     */
    private static final class Recording implements RecordHandler {
        private final List<ConsumerRecord<String, String>> received = Collections.synchronizedList(new ArrayList<>());
        private final List<String> tried = Collections.synchronizedList(new ArrayList<>());
        private final PerKeyConcurrency concurrency;
        private final Duration hold;
        private volatile String failKey;
        private volatile String rejectKey;
        private volatile Gate gate;
        private volatile String flakyKey;
        private volatile String flakyEventId;
        private final AtomicInteger flakyFailuresLeft = new AtomicInteger();
        private final Map<String, Long> firstAttemptNanos = new ConcurrentHashMap<>();

        Recording() {
            this(null, Duration.ZERO);
        }

        Recording(PerKeyConcurrency concurrency, Duration hold) {
            this.concurrency = concurrency;
            this.hold = hold;
        }

        /** 그 키의 첫 레코드 처리를 {@code gate} 가 열릴 때까지 붙잡는다. 여러 인스턴스의 핸들러가 같은 문을 나눠 쓴다. */
        void holdFirstRecordOf(Gate shared) {
            gate = shared;
        }

        void failFirstRecordOf(String key, int failures) {
            flakyKey = key;
            flakyFailuresLeft.set(failures);
        }

        List<String> ids() {
            synchronized (received) {
                return received.stream().map(Recording::eventId).toList();
            }
        }

        List<String> idsOf(String key) {
            synchronized (received) {
                return received.stream().filter(record -> key.equals(record.key())).map(Recording::eventId).toList();
            }
        }

        String firstPayload() {
            synchronized (received) {
                return received.get(0).value();
            }
        }

        int attempts(String eventId) {
            synchronized (tried) {
                return (int) tried.stream().filter(eventId::equals).count();
            }
        }

        /** 처음 시도한 시각({@link System#nanoTime()}). 아직 시도하지 않았으면 {@code null}. */
        Long firstAttemptNanos(String eventId) {
            return firstAttemptNanos.get(eventId);
        }

        int firstAttemptIndex(String eventId) {
            synchronized (tried) {
                return tried.indexOf(eventId);
            }
        }

        int lastAttemptIndex(String eventId) {
            synchronized (tried) {
                return tried.lastIndexOf(eventId);
            }
        }

        private static String eventId(ConsumerRecord<String, String> record) {
            return EventHeaders.lastValue(record.headers(), EventHeaders.EVENT_ID);
        }

        @Override
        public void receive(ConsumerRecord<String, String> record) {
            String eventId = eventId(record);
            firstAttemptNanos.putIfAbsent(eventId, System.nanoTime());
            tried.add(eventId);
            if (concurrency != null) {
                concurrency.enter(record.key());
            }
            try {
                Gate shared = gate;
                if (shared != null && shared.key.equals(record.key()) && shared.claim(eventId)) {
                    shared.entered.countDown();
                    if (!shared.release.await(2, TimeUnit.MINUTES)) {
                        throw new IllegalStateException("문이 열리지 않았다");
                    }
                }
                if (!hold.isZero()) {
                    Thread.sleep(hold.toMillis());
                }
                if (record.key().equals(failKey)) {
                    throw new IllegalStateException("boom");
                }
                if (record.key().equals(rejectKey)) {
                    throw new Rejected();
                }
                if (record.key().equals(flakyKey)) {
                    if (flakyEventId == null) {
                        flakyEventId = eventId;
                    }
                    if (eventId.equals(flakyEventId) && flakyFailuresLeft.getAndDecrement() > 0) {
                        throw new IllegalStateException("잠깐 실패");
                    }
                }
                received.add(record);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            } finally {
                if (concurrency != null) {
                    concurrency.exit(record.key());
                }
            }
        }
    }
}
