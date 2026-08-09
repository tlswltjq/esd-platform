package com.stove.common.testcontainers;

import java.time.Duration;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.MySQLContainer;
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
 */
public final class SharedContainers {

    public static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>(DockerImageName.parse("mysql:8.0"));

    /**
     * 로컬 실행은 {@code apache/kafka:3.9.0} 을 쓰지만 테스트는 Confluent 이미지를 쓴다.
     *
     * <p>Testcontainers 1.21 의 {@code kafka.KafkaContainer} 와 {@code apache/kafka:3.9.0} 조합에서는
     * 이미지 엔트리포인트가 광고 리스너를 주입받기 전에 스토리지를 포맷하면서
     * {@code advertised.listeners cannot use the nonroutable meta-address 0.0.0.0} 으로 죽는다.
     * 컨텍스트 로딩 검증에 브로커 구현 차이는 영향이 없으므로 안정적인 쪽을 택했다.
     */
    public static final ConfluentKafkaContainer KAFKA =
            new ConfluentKafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.8.0"));

    public static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379);

    public static final ElasticsearchContainer ELASTICSEARCH =
            new ElasticsearchContainer(
                    DockerImageName.parse("docker.elastic.co/elasticsearch/elasticsearch:8.18.6"))
                    .withEnv("xpack.security.enabled", "false")
                    .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m")
                    .withStartupTimeout(Duration.ofMinutes(3));

    public static final MongoDBContainer MONGO =
            new MongoDBContainer(DockerImageName.parse("mongo:7"));

    private SharedContainers() {
    }
}
