package com.stove.common.testcontainers;

import java.util.Map;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 앱이 필요한 인프라만 골라 {@code @Import} 하는 테스트 설정 모음.
 *
 * <p>{@code @ServiceConnection} 이 컨테이너의 접속 정보를 스프링 설정으로 밀어 넣으므로
 * 테스트 쪽에 URL/포트를 따로 쓰지 않는다.
 */
public final class InfraContainers {

    private static final String MINIO_TAG = "RELEASE.2025-04-22T22-12-26Z-local";

    private InfraContainers() {
    }

    /** 기본 이미지는 Gradle이 준비하는 고정 MinIO/mc 릴리즈의 Jib 이미지다. */
    public static DockerImageName minioImage() {
        return minioImage(System.getenv());
    }

    /**
     * 테스트 이름 > 공급 이미지 > {@code <registry>/minio:<태그>} 순서로 고른다. Gradle 통합 테스트는
     * 루트 build.gradle이 고른 이름을 {@code STOVE_TEST_MINIO_IMAGE}로 넘긴다. Docker Hub의 stove 계정을
     * 가리키는 값은 받지 않고 거절한다(결정 118).
     */
    static DockerImageName minioImage(Map<String, String> environment) {
        String image = LocalImageNames.requireNotDockerHubStove(
                "STOVE_TEST_MINIO_IMAGE", value(environment, "STOVE_TEST_MINIO_IMAGE"));
        String supplied = LocalImageNames.requireNotDockerHubStove(
                "STOVE_MINIO_IMAGE", value(environment, "STOVE_MINIO_IMAGE"));
        String registry = LocalImageNames.requireRegistryNotDockerHubStove(
                "STOVE_JIB_REGISTRY", value(environment, "STOVE_JIB_REGISTRY"));
        if (image.isEmpty()) {
            image = supplied;
        }
        if (image.isEmpty()) {
            image = (registry.isEmpty() ? LocalImageNames.DEFAULT_REGISTRY : registry) + "/minio:" + MINIO_TAG;
        }
        return DockerImageName.parse(image).asCompatibleSubstituteFor("minio/minio");
    }

    private static String value(Map<String, String> environment, String name) {
        String value = environment.get(name);
        return value == null ? "" : value.strip();
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class MySql {

        @Bean
        @ServiceConnection
        MySQLContainer<?> mysqlContainer() {
            return SharedContainers.MYSQL;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class Kafka {

        @Bean
        @ServiceConnection
        ConfluentKafkaContainer kafkaContainer() {
            return SharedContainers.KAFKA;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class Redis {

        @Bean
        @ServiceConnection(name = "redis")
        GenericContainer<?> redisContainer() {
            return SharedContainers.REDIS;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class Elasticsearch {

        @Bean
        @ServiceConnection
        ElasticsearchContainer elasticsearchContainer() {
            return SharedContainers.ELASTICSEARCH;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class Mongo {

        @Bean
        @ServiceConnection
        MongoDBContainer mongoContainer() {
            return SharedContainers.MONGO;
        }
    }
}
