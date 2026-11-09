package com.stove.common.testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * D-043: 공유 Kafka 컨테이너는 준비 로그가 기본 대기(60초)보다 늦게 나와도 시작을 기다린다.
 *
 * <p>실제 Kafka를 늦출 수 없으므로, Kafka 준비 로그를 65초 뒤에 내는 가짜 컨테이너에 {@link SharedContainers#kafkaReady()}를
 * 건다. 이미지는 공유 Redis와 같은 {@code redis:7-alpine}이라 새로 받을 이미지가 없다. 대기 전략을 라이브러리 기본값(60초)으로
 * 되돌리면 이 테스트는 {@code ContainerLaunchException}으로 실패한다(PR #138 본문).
 */
class SharedContainersStartupIntegrationTest {

    @Test
    void aKafkaThatLogsReadyAfterTheDefaultWaitStillStarts() {
        try (GenericContainer<?> slowKafka = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                .withCommand("sh", "-c",
                        "sleep 65; echo 'INFO [KafkaRaftServer nodeId=1] Transitioning from RECOVERY to RUNNING'; sleep 600")
                .waitingFor(SharedContainers.kafkaReady())) {
            slowKafka.start();
            assertThat(slowKafka.isRunning()).isTrue();
        }
    }
}
