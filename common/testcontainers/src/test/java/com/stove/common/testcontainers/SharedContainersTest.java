package com.stove.common.testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.wait.strategy.AbstractWaitStrategy;
import org.testcontainers.containers.wait.strategy.WaitStrategy;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * D-043: 공유 컨테이너 하나가 시작 대기를 넘기면 그 모듈의 통합 테스트 컨텍스트가 모두 실패한다.
 * CI run 42410179110에서 Kafka가 기본 60초 안에 시작 로그를 내지 못해 Settlement 통합 10건이 함께 실패했다
 * (같은 run의 다른 Kafka 시작은 5~9초). 러너 부하는 결정적으로 재현할 수 없으므로, 공유 컨테이너마다
 * 기본값이 아닌 3분 대기를 갖는지를 고정한다.
 *
 * <p>Testcontainers 1.21은 실제로 쓰는 대기 객체를 {@code getWaitStrategy()}로 고른다. 필드가 공유 기본값이면
 * {@code ContainerDef}의 대기 객체를 쓰므로, 필드가 아니라 그 메서드의 결과를 읽는다.
 */
class SharedContainersTest {
    private static final Duration EXPECTED = Duration.ofMinutes(3);

    @Test
    @DisplayName("[D-054] 공유 Kafka는 복사가 끝난 시작 스크립트만 실행 경로에 게시한다")
    void sharedKafkaPublishesOnlyCompleteStarterScripts() {
        assertThat(SharedContainers.KAFKA).isInstanceOf(AtomicStarterKafkaContainer.class);
    }

    @Test
    @DisplayName("[D-043] 공유 컨테이너는 모두 기본값(60초·120초)이 아닌 3분 시작 대기를 갖는다")
    void everySharedContainerWaitsThreeMinutesForStartup() throws ReflectiveOperationException {
        Map<String, Duration> actual = new LinkedHashMap<>();
        actual.put("mysql", Duration.ofSeconds(jdbcStartupSeconds(SharedContainers.MYSQL)));
        actual.put("kafka", waitTimeout(SharedContainers.KAFKA));
        actual.put("redis", waitTimeout(SharedContainers.REDIS));
        actual.put("elasticsearch", waitTimeout(SharedContainers.ELASTICSEARCH));
        actual.put("mongo", waitTimeout(SharedContainers.MONGO));

        Map<String, Duration> expected = new LinkedHashMap<>();
        for (String name : actual.keySet()) expected.put(name, EXPECTED);
        assertThat(actual).containsExactlyEntriesOf(expected);
    }

    @Test
    @DisplayName("[D-043] 대기 시간을 늘린 컨테이너는 모든 컨테이너가 나눠 쓰는 기본 대기 객체를 바꾸지 않는다")
    void longerWaitsDoNotMutateTheSharedDefaultWaitStrategy() throws ReflectiveOperationException {
        Field field = GenericContainer.class.getDeclaredField("DEFAULT_WAIT_STRATEGY");
        field.setAccessible(true);
        WaitStrategy shared = (WaitStrategy) field.get(null);
        List<GenericContainer<?>> containers = List.of(SharedContainers.KAFKA, SharedContainers.REDIS,
                SharedContainers.ELASTICSEARCH, SharedContainers.MONGO);
        for (GenericContainer<?> container : containers)
            assertThat(waitStrategy(container)).as(container.getDockerImageName()).isNotSameAs(shared);
        assertThat(startupTimeout(shared)).isEqualTo(Duration.ofSeconds(60));
    }

    /**
     * 라이브러리가 컨테이너 종류마다 정적 대기 객체를 나눠 쓰기도 한다(예: {@code ConfluentKafkaContainer}의
     * {@code KafkaHelper.WAIT_STRATEGY}). 같은 종류의 새 컨테이너와 대기 객체가 다르고, 새 컨테이너는
     * 라이브러리 기본 대기를 그대로 가져야 공유 컨테이너의 3분이 다른 컨테이너로 번지지 않은 것이다.
     */
    @Test
    @DisplayName("[D-043] 공유 컨테이너의 3분 대기는 같은 종류의 새 컨테이너로 번지지 않는다")
    void longerWaitsDoNotLeakIntoFreshContainersOfTheSameType() throws ReflectiveOperationException {
        Map<String, GenericContainer<?>> shared = new LinkedHashMap<>();
        shared.put("kafka", SharedContainers.KAFKA);
        shared.put("redis", SharedContainers.REDIS);
        shared.put("elasticsearch", SharedContainers.ELASTICSEARCH);
        shared.put("mongo", SharedContainers.MONGO);
        Map<String, GenericContainer<?>> fresh = new LinkedHashMap<>();
        fresh.put("kafka", new ConfluentKafkaContainer(DockerImageName.parse(SharedContainers.KAFKA.getDockerImageName())));
        fresh.put("redis", new GenericContainer<>(DockerImageName.parse(SharedContainers.REDIS.getDockerImageName())));
        fresh.put("elasticsearch", new ElasticsearchContainer(DockerImageName.parse(SharedContainers.ELASTICSEARCH.getDockerImageName())));
        fresh.put("mongo", new MongoDBContainer(DockerImageName.parse(SharedContainers.MONGO.getDockerImageName())));

        for (String name : shared.keySet())
            assertThat(waitStrategy(fresh.get(name))).as(name).isNotSameAs(waitStrategy(shared.get(name)));
        Map<String, Duration> freshTimeouts = new LinkedHashMap<>();
        for (String name : List.of("kafka", "redis", "mongo")) freshTimeouts.put(name, waitTimeout(fresh.get(name)));
        assertThat(freshTimeouts).containsExactlyInAnyOrderEntriesOf(
                Map.of("kafka", Duration.ofSeconds(60), "redis", Duration.ofSeconds(60), "mongo", Duration.ofSeconds(60)));
    }

    /**
     * 결정 222: S3 측정용 한도(메모리 1GB·Kafka 힙·Redis maxmemory)는 {@code stove.contract.s3=true} 일 때만 걸린다.
     * 다른 서비스도 같은 공유 컨테이너를 쓰므로, 평소에는 같은 종류의 새 컨테이너와 설정이 같아야 한다.
     */
    @Test
    @DisplayName("[결정 222] S3 측정을 켜지 않으면 공유 MySQL·Kafka·Redis 에 메모리 한도·힙·maxmemory 가 걸리지 않는다")
    void s3LimitsAreOffByDefault() {
        assertThat(SharedContainers.s3Measurement()).isFalse();
        int freshModifiers = new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).getCreateContainerCmdModifiers().size();
        Map<String, Integer> modifiers = new LinkedHashMap<>();
        modifiers.put("mysql", SharedContainers.MYSQL.getCreateContainerCmdModifiers().size());
        modifiers.put("kafka", SharedContainers.KAFKA.getCreateContainerCmdModifiers().size());
        modifiers.put("redis", SharedContainers.REDIS.getCreateContainerCmdModifiers().size());
        assertThat(modifiers).containsExactlyInAnyOrderEntriesOf(Map.of("mysql", freshModifiers, "kafka", freshModifiers, "redis", freshModifiers));
        assertThat(SharedContainers.KAFKA.getEnvMap()).doesNotContainKey("KAFKA_HEAP_OPTS");
        assertThat(SharedContainers.REDIS.getCommandParts()).isEmpty();
    }

    private static WaitStrategy waitStrategy(GenericContainer<?> container) throws ReflectiveOperationException {
        Method method = GenericContainer.class.getDeclaredMethod("getWaitStrategy");
        method.setAccessible(true);
        return (WaitStrategy) method.invoke(container);
    }

    private static Duration waitTimeout(GenericContainer<?> container) throws ReflectiveOperationException {
        return startupTimeout(waitStrategy(container));
    }

    private static Duration startupTimeout(WaitStrategy strategy) throws ReflectiveOperationException {
        assertThat(strategy).isInstanceOf(AbstractWaitStrategy.class);
        Field timeout = AbstractWaitStrategy.class.getDeclaredField("startupTimeout");
        timeout.setAccessible(true);
        return (Duration) timeout.get(strategy);
    }

    private static int jdbcStartupSeconds(JdbcDatabaseContainer<?> container) throws ReflectiveOperationException {
        Field field = JdbcDatabaseContainer.class.getDeclaredField("startupTimeoutSeconds");
        field.setAccessible(true);
        return field.getInt(container);
    }
}
