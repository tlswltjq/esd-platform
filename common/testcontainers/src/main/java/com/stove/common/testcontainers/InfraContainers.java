package com.stove.common.testcontainers;

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

    private InfraContainers() {
    }

    /** 공개 레지스트리가 불가한 환경에서는 같은 릴리즈의 로컬 소스 빌드를 선택한다. */
    public static DockerImageName minioImage() {
        String image = System.getenv("STOVE_TEST_MINIO_IMAGE");
        if (image == null || image.isBlank()) {
            image = "quay.io/minio/minio:RELEASE.2025-04-22T22-12-26Z";
        }
        return DockerImageName.parse(image).asCompatibleSubstituteFor("minio/minio");
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
