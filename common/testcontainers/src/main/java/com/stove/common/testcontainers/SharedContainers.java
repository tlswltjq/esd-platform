package com.stove.common.testcontainers;

import java.time.Duration;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.containers.wait.strategy.WaitStrategy;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 테스트용 인프라 컨테이너.
 *
 * <p>정적 싱글턴이라 같은 JVM(= 같은 Gradle 모듈) 안에서는 한 번만 뜬다.
 * 이미지 태그는 {@code docker-compose.yml} 과 맞춰 두었다 — 로컬에 이미 받아둔 이미지를
 * 그대로 쓰고, 테스트와 로컬 실행이 같은 버전을 보게 하기 위함이다.
 *
 * <p>컨테이너를 모듈 간에 재사용하지 않는다. 재사용하면 앞선 모듈이 남긴
 * {@code flyway_schema_history} 때문에 다음 모듈의 마이그레이션 검증이 깨진다 —
 * 모듈마다 깨끗한 인프라에서 시작하는 편이 검증으로서도 정확하다.
 *
 * <p>모든 컨테이너는 {@link #STARTUP_TIMEOUT}까지 시작을 기다린다(D-043). 기본값(로그·포트 60초, JDBC 120초)은
 * 부하가 몰린 CI 러너에서 모자라, 컨테이너 하나가 늦으면 그 모듈의 통합 테스트 컨텍스트가 모두 실패했다.
 * 대기는 실패할 때만 길어지고, 정상 시작(보통 10초 안)에는 영향이 없다.
 */
public final class SharedContainers {

    static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(3);

    /** Confluent Kafka(KRaft)가 준비되면 내는 로그. 라이브러리의 기본 대기 조건과 같다. */
    static final String KAFKA_READY_LOG = ".*Transitioning from RECOVERY to RUNNING.*";

    /** D-043: Kafka 준비 로그를 {@link #STARTUP_TIMEOUT}까지 기다리는 새 대기 객체. 재현 테스트가 같은 객체로 검증한다. */
    static WaitStrategy kafkaReady() {
        return Wait.forLogMessage(KAFKA_READY_LOG, 1).withStartupTimeout(STARTUP_TIMEOUT);
    }

    public static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
                    .withStartupTimeoutSeconds((int) STARTUP_TIMEOUT.toSeconds());

    /**
     * 로컬 실행은 {@code apache/kafka:3.9.0} 을 쓰지만 테스트는 Confluent 이미지를 쓴다.
     *
     * <p>Testcontainers 1.21 의 {@code kafka.KafkaContainer} 와 {@code apache/kafka:3.9.0} 조합에서는
     * 이미지 엔트리포인트가 광고 리스너를 주입받기 전에 스토리지를 포맷하면서
     * {@code advertised.listeners cannot use the nonroutable meta-address 0.0.0.0} 으로 죽는다.
     * 컨텍스트 로딩 검증에 브로커 구현 차이는 영향이 없으므로 안정적인 쪽을 택했다.
     *
     * <p>{@code ConfluentKafkaContainer}는 모든 인스턴스가 정적 {@code KafkaHelper.WAIT_STRATEGY}를 나눠 쓴다.
     * {@code withStartupTimeout}만 걸면 같은 JVM의 다른 Kafka 컨테이너까지 바뀌므로, 같은 로그 조건으로
     * 새 대기 객체를 준다.
     */
    public static final ConfluentKafkaContainer KAFKA =
            new AtomicStarterKafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.8.0"))
                    .waitingFor(kafkaReady());

    public static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379)
                    // 새 대기 객체를 쓴다. withStartupTimeout만 걸면 GenericContainer가 모든 컨테이너와
                    // 나눠 쓰는 기본 대기 객체(DEFAULT_WAIT_STRATEGY)의 시간이 바뀐다.
                    .waitingFor(Wait.forListeningPort().withStartupTimeout(STARTUP_TIMEOUT));

    public static final ElasticsearchContainer ELASTICSEARCH =
            new ElasticsearchContainer(
                    DockerImageName.parse("docker.elastic.co/elasticsearch/elasticsearch:8.18.6"))
                    .withEnv("xpack.security.enabled", "false")
                    .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m")
                    .withStartupTimeout(STARTUP_TIMEOUT);

    public static final MongoDBContainer MONGO =
            new MongoDBContainer(DockerImageName.parse("mongo:7"))
                    .withStartupTimeout(STARTUP_TIMEOUT);

    private SharedContainers() {
    }
}
