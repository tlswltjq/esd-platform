package com.stove.common.testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.wait.strategy.AbstractWaitStrategy;
import org.testcontainers.containers.wait.strategy.WaitStrategy;

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
    @Tag("known-defect")
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
    @Tag("known-defect")
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
