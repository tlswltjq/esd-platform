package com.stove.common.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import com.stove.common.testcontainers.SharedContainers;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.kafka.KafkaClientMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

/**
 * 컨슈머가 멈췄을 때 <b>적체를 무엇으로 판정하는가</b>.
 *
 * <p>두 가지를 같은 상황에 놓고 잰다. 상황은 하나다 — 컨슈머가 정상 동작을 한 번 증명한 뒤
 * 멈추고, 그 뒤에 백로그가 쌓인다. 운영에서 랙 알람이 필요한 상황이 정확히 이것이다.
 *
 * <ul>
 *   <li>{@link #brokerOffsetsSeeBacklog} — 브로커의 커밋 오프셋. <b>맞는다.</b>
 *       {@code scripts/perf/collect-lag.sh} 와 compose 의 {@code kafka-exporter} 가 쓰는 계산이고,
 *       알람 규칙({@code infra/prometheus/alerts.yml} 의 {@code stove-consumer})이 여기 걸려 있다.
 *       이 테스트는 그 판정 근거의 회귀 방어선이다</li>
 *   <li>{@link #appMetricGoesSilent} — 앱이 노출하는 {@code kafka.consumer.fetch.manager.records.lag}.
 *       <b>틀린다.</b> <a href="../../../../../../../docs/defects.md">D-026</a></li>
 * </ul>
 *
 * <p>둘을 한 클래스에 두는 이유 — <b>이 결함의 요점은 "지표가 틀렸다" 가 아니라 "무엇을 믿어야
 * 하는가" 다.</b> 나란히 두지 않으면 다음 사람이 같은 선택을 다시 한다.
 *
 * <p>여기서 읽는 지표는 우리가 계측한 것이 아니라 Kafka 클라이언트가 내는 값을 Micrometer 의
 * {@link KafkaClientMetrics} 가 옮긴 것이다. 스프링 부트가 액추에이터에 노출할 때 쓰는 바인더가
 * 같은 것이라, 이 테스트가 읽는 값과 {@code /actuator/prometheus} 에 찍히는 값은 같다.
 */
class ConsumerLagMetricTest {

    /** 컨슈머가 멈춘 뒤 쌓을 건수. 지표가 "조금 틀린" 것이 아님을 보이려면 커야 한다. */
    private static final int BACKLOG = 5_000;

    private static String bootstrap;

    @BeforeAll
    static void startBroker() {
        SharedContainers.KAFKA.start();
        bootstrap = SharedContainers.KAFKA.getBootstrapServers();
    }

    @Test
    @DisplayName("브로커의 커밋 오프셋은 컨슈머가 멈춘 뒤 쌓인 백로그를 그대로 센다")
    void brokerOffsetsSeeBacklog(TestInfo info) throws Exception {
        Fixture fixture = stalledConsumerWithBacklog(info);

        // 컨슈머가 죽어 있어도 커밋 오프셋과 로그 끝 오프셋은 브로커에 남아 있다.
        // **컨슈머가 죽었을 때야말로 랙을 알아야 하므로** 이 성질이 판정 근거의 자격이다.
        assertThat(fixture.brokerLag())
                .as("kafka-consumer-groups.sh --describe 와 kafka-exporter 가 쓰는 계산")
                .isEqualTo(BACKLOG);
    }

    @Test
    @Tag("known-defect")
    @DisplayName("[D-026] 컨슈머가 멈춘 뒤 쌓인 백로그를 앱 랙 지표가 보고하지 않는다")
    void appMetricGoesSilent(TestInfo info) throws Exception {
        Fixture fixture = stalledConsumerWithBacklog(info);

        // 같은 순간, 같은 백로그. 브로커는 5,000 을 아는데(위 테스트) 지표는 0 을 말한다.
        // 값이 틀린 것이 아니라 **갱신이 멈춘 것**이다 — 이 값은 직전 fetch 응답에서 본 것이고,
        // fetch 가 멈추면 값도 멈춘다. 랙이 위험한 상황은 대개 컨슈머가 멈춘 상황이므로,
        // 지표가 가장 필요한 순간에 지표가 침묵한다.
        assertThat(fixture.reportedLag())
                .as("컨슈머가 멈춘 동안 쌓인 %d건을 랙 지표가 보고해야 한다", BACKLOG)
                .isEqualTo(BACKLOG);
    }

    /**
     * 두 테스트가 공유하는 상황을 만든다.
     *
     * <p>토픽과 그룹을 테스트마다 따로 쓴다 — 같이 쓰면 앞 테스트가 커밋한 오프셋이
     * 뒤 테스트의 시작 상태가 되어, 둘이 같은 상황을 보고 있다는 전제가 깨진다.
     */
    private Fixture stalledConsumerWithBacklog(TestInfo info) throws Exception {
        String name = "d026-" + info.getTestMethod().orElseThrow().getName().toLowerCase();
        TopicPartition partition = new TopicPartition(name, 0);

        try (Admin admin = admin()) {
            // 파티션 1개로 둔다. 재현에 파티션 수는 상관이 없고, 적을수록 판정이 단순하다.
            admin.createTopics(List.of(new NewTopic(name, 1, (short) 1))).all().get();
        }

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        KafkaConsumer<String, String> consumer = consumer(name);
        consumer.subscribe(List.of(name));

        // 1. 정상 동작을 먼저 증명한다. 이 단계가 없으면 "원래 안 붙어 있었다"와 구분되지 않는다.
        send(name, 10);
        assertThat(drain(consumer)).isEqualTo(10);
        consumer.commitSync();

        // 2. 빈 폴을 한 번 더 돌려 파티션 지표가 "랙 0" 으로 자리잡게 한다.
        //    바인딩을 이 뒤에 하는 이유 — 파티션별 지표는 fetch 가 한 번 돌아야 생긴다.
        consumer.poll(Duration.ofMillis(500));
        KafkaClientMetrics metrics = new KafkaClientMetrics(consumer);
        metrics.bindTo(registry);
        assertThat(reportedLag(registry)).as("멈추기 전에는 지표가 붙어 있다").isZero();

        // 3. **여기서 컨슈머가 멈춘다.** 이후 poll 을 부르지 않는다 —
        //    죽었거나, 리밸런싱에 묶였거나, 리스너가 한 건에서 재시도를 돌고 있는 상태다.
        send(name, BACKLOG);

        return new Fixture(name, partition, registry, consumer, metrics);
    }

    /**
     * 컨슈머를 열어 둔 채로 판정한다 — 닫으면 지표도 같이 사라져 "0 을 보고한다"와
     * "지표가 없다"를 구분할 수 없다. JUnit 이 인스턴스를 버릴 때 함께 정리된다.
     */
    private record Fixture(String group, TopicPartition partition, SimpleMeterRegistry registry,
                           KafkaConsumer<String, String> consumer, KafkaClientMetrics metrics) {

        /** 액추에이터가 노출하는 것과 같은 지표. 파티션별로 나오므로 합산한다. */
        long reportedLag() {
            return ConsumerLagMetricTest.reportedLag(registry);
        }

        /** 브로커 기준 랙. {@code kafka-consumer-groups.sh --describe} 가 내는 것과 같은 계산이다. */
        long brokerLag() throws Exception {
            try (Admin admin = admin()) {
                long committed = admin.listConsumerGroupOffsets(group)
                        .partitionsToOffsetAndMetadata().get()
                        .get(partition).offset();
                long logEnd = admin.listOffsets(Map.of(partition, OffsetSpec.latest()))
                        .all().get()
                        .get(partition).offset();
                return logEnd - committed;
            }
        }
    }

    private static long reportedLag(SimpleMeterRegistry registry) {
        return (long) registry.find("kafka.consumer.fetch.manager.records.lag")
                .gauges().stream()
                .mapToDouble(Gauge::value)
                .filter(value -> !Double.isNaN(value))
                .sum();
    }

    /** 한 건도 안 오는 폴이 연속 세 번 나오면 다 읽은 것으로 본다. */
    private static int drain(KafkaConsumer<String, String> consumer) {
        int total = 0;
        int empty = 0;
        while (empty < 3) {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
            if (records.isEmpty()) {
                empty++;
            } else {
                empty = 0;
                total += records.count();
            }
        }
        return total;
    }

    private static void send(String topic, int count) {
        try (KafkaProducer<String, String> producer = producer()) {
            for (int i = 0; i < count; i++) {
                producer.send(new ProducerRecord<>(topic, "k" + i, "v" + i));
            }
            producer.flush();
        }
    }

    private static Admin admin() {
        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        return Admin.create(props);
    }

    private static KafkaProducer<String, String> producer() {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        return new KafkaProducer<>(props);
    }

    private static KafkaConsumer<String, String> consumer(String group) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, group);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        return new KafkaConsumer<>(props);
    }
}
